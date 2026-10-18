import { apiUrl } from '@/api/http'
import type { ChatEvent, ChatHandlers, ChatRequest } from '@/api/chat'

/**
 * 对话事件流解析。
 *
 * 不用 EventSource：POST /api/chat 需要请求体，而 EventSource 只支持 GET。
 * 这里用 fetch + ReadableStream 手动解析 text/event-stream。
 *
 * 关键约定：
 * - FINISHED 是唯一终止信号，收到即视为本轮结束，不再等连接关闭
 * - ERROR 只是流内提示，不终止流，继续等 FINISHED
 * - 跨 chunk 被截断的半个事件块要留在缓冲区里
 */

interface RawEvent {
  event: string
  data: string
}

/** 按空行切分事件块，返回完整块与剩余缓冲 */
function splitBuffer(buffer: string): { blocks: string[]; rest: string } {
  const normalized = buffer.replace(/\r\n/g, '\n')
  const parts = normalized.split('\n\n')
  const rest = parts.pop() ?? ''
  return {
    blocks: parts.filter((block) => block.trim().length > 0),
    rest,
  }
}

function parseBlock(block: string): RawEvent | null {
  let event = ''
  const dataLines: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith(':')) {
      // 注释行（后端心跳），跳过
      continue
    }
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
      continue
    }
    if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''))
      continue
    }
    // 其它字段（id/retry）当前后端不发送，忽略
  }
  if (!event && dataLines.length === 0) {
    return null
  }
  return { event, data: dataLines.join('\n') }
}

function toChatEvent(raw: RawEvent): ChatEvent | null {
  if (!raw.data) {
    return null
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw.data)
  } catch {
    return null
  }
  if (typeof parsed !== 'object' || parsed === null) {
    return null
  }
  const payload = parsed as ChatEvent & { type?: string }
  // 事件名与载荷 type 都给出时以载荷为准，载荷缺失时退回事件名
  if (!payload.type && raw.event) {
    ;(payload as { type: string }).type = raw.event
  }
  return payload
}

export class ChatStreamBusyError extends Error {
  readonly status: number
  readonly code: number

  constructor(message: string, status = 0, code = 0) {
    super(message)
    this.name = 'ChatStreamBusyError'
    this.status = status
    this.code = code
  }
}

/**
 * 发起一轮对话并逐事件回调。
 * 返回值在本轮结束时（收到 FINISHED 或断流）兑现，不抛出业务异常——
 * 事件流一旦开始推送，HTTP 状态已经是 200，错误只能由 ERROR/FINISHED 表达。
 */
export async function streamChat(
  request: ChatRequest,
  handlers: ChatHandlers,
  signal?: AbortSignal,
): Promise<void> {
  let response: Response
  try {
    response = await fetch(apiUrl('/api/chat'), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      },
      body: JSON.stringify(request),
      signal,
    })
  } catch {
    if (signal?.aborted) {
      return
    }
    handlers.onDisconnect?.('network')
    return
  }

  if (!response.ok || !response.body) {
    // 流没起来：HTTP 层失败，读取后端返回的业务码。
    // 注意会话繁忙（2002）不会走到这里——同一 sessionId 已有回合在跑时，
    // 后端仍以 HTTP 200 起流，然后用流内 ERROR(2002) + FINISHED(FAILED) 结束。
    let message = `对话请求失败（HTTP ${response.status}）`
    let code = response.status
    try {
      const body = (await response.json()) as { code?: number; message?: string }
      if (body?.message) message = body.message
      if (typeof body?.code === 'number') code = body.code
    } catch {
      // 非 JSON 错误体，沿用状态码
    }
    throw new ChatStreamBusyError(message, response.status, code)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let finished = false

  try {
    while (!finished) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true })
      const { blocks, rest } = splitBuffer(buffer)
      buffer = rest

      for (const block of blocks) {
        const raw = parseBlock(block)
        if (!raw) continue
        const event = toChatEvent(raw)
        if (!event) {
          // 单条事件解析失败只跳过，不打断整条流
          handlers.onParseError?.(raw.data, new Error('事件载荷无法解析'))
          continue
        }
        handlers.onEvent(event)
        if (event.type === 'FINISHED') {
          finished = true
          break
        }
      }
    }

    // 收尾：缓冲区里可能还有最后一个未被空行结束的块
    if (!finished && buffer.trim().length > 0) {
      const raw = parseBlock(buffer.replace(/\r\n/g, '\n'))
      const event = raw ? toChatEvent(raw) : null
      if (event) {
        handlers.onEvent(event)
        if (event.type === 'FINISHED') {
          finished = true
        }
      }
    }
  } finally {
    // 主动中断或正常结束时都要释放读取器，避免连接悬挂
    try {
      await reader.cancel()
    } catch {
      // 已关闭的连接再次 cancel 会抛，忽略
    }
  }

  if (!finished) {
    if (signal?.aborted) {
      return
    }
    handlers.onDisconnect?.('closed')
  }
}
