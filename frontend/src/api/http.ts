import { ApiError, ResultCode, isApiError, type ApiResponse } from './types'

/**
 * 统一 HTTP 客户端。
 *
 * 开发期 /api 由 vite 代理到 VITE_API_BASE_URL，因此默认走同源相对路径；
 * 只有显式配置成绝对地址时才直连后端。这样避免跨域与 SSE 预检问题。
 */

const TIMEOUT_MS = 10_000

function resolveBase(): string {
  const raw = import.meta.env.VITE_API_BASE_URL
  if (!raw) {
    // 未配置时退回同源，由 vite 代理转发；生产构建必须显式配置
    if (import.meta.env.DEV) {
      return ''
    }
    throw new Error(
      '缺少 VITE_API_BASE_URL：构建前请在 .env 中配置后端地址，参考 .env.example',
    )
  }
  return raw.replace(/\/$/, '')
}

const BASE_URL = resolveBase()

/** 拼接请求地址。相对 base 时直接返回路径，便于走代理 */
export function apiUrl(path: string): string {
  return `${BASE_URL}${path}`
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  signal?: AbortSignal
  /** 覆盖默认超时。编译、重建、跑批这类同步长任务需要放宽 */
  timeoutMs?: number
  /** 直接传原始 body（SSE 用） */
  rawBody?: string
  headers?: Record<string, string>
  /** 响应解析方式：JSON（默认）或原始文本 */
  responseType?: 'json' | 'text'
}

export type RequestResult<T> = {
  data: T
  code: number
  message: string
}

function toApiError(error: unknown, httpStatus: number): ApiError {
  const body = error as Partial<ApiResponse<unknown>> | undefined
  const code: number = typeof body?.code === 'number' ? body.code : httpStatus
  const message =
    typeof body?.message === 'string' && body.message.length > 0
      ? body.message
      : `请求失败（HTTP ${httpStatus}）`
  return new ApiError(message, {
    code,
    httpStatus,
    kind: 'business',
    detail: message,
  })
}

function createTimeoutSignal(
  outer: AbortSignal | undefined,
  timeoutMs: number,
): { signal: AbortSignal; cleanup: () => void; isTimedOut: () => boolean } {
  const controller = new AbortController()
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)

  const onAbort = () => controller.abort()
  if (outer) {
    if (outer.aborted) {
      controller.abort()
    } else {
      outer.addEventListener('abort', onAbort, { once: true })
    }
  }

  return {
    signal: controller.signal,
    isTimedOut: () => timedOut,
    cleanup: () => {
      clearTimeout(timer)
      outer?.removeEventListener('abort', onAbort)
    },
  }
}

/**
 * 发一次请求并解析统一返回体。
 * 判定顺序：网络/超时 -> HTTP 状态 -> 业务码；成功码是 200。
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const {
    method = 'GET',
    body,
    signal,
    timeoutMs = TIMEOUT_MS,
    rawBody,
    headers = {},
    responseType = 'json',
  } = options

  const { signal: merged, cleanup, isTimedOut } = createTimeoutSignal(signal, timeoutMs)

  let response: Response
  try {
    response = await fetch(apiUrl(path), {
      method,
      headers: {
        Accept: responseType === 'json' ? 'application/json' : '*/*',
        ...(body !== undefined || rawBody !== undefined
          ? { 'Content-Type': 'application/json' }
          : {}),
        ...headers,
      },
      body: rawBody ?? (body === undefined ? undefined : JSON.stringify(body)),
      signal: merged,
    })
  } catch (error) {
    cleanup()
    if (isTimedOut()) {
      throw new ApiError('请求超时：后端 10 秒内没有响应', { kind: 'timeout', code: -1 })
    }
    if (signal?.aborted) {
      throw new ApiError('请求已取消', { kind: 'canceled', code: -2 })
    }
    throw new ApiError('网络异常：无法连接到后端服务', {
      kind: 'network',
      code: -3,
      detail: error instanceof Error ? error.message : String(error),
    })
  }
  cleanup()

  if (!response.ok) {
    let parsed: unknown
    try {
      parsed = await response.json()
    } catch {
      parsed = undefined
    }
    // SSE 一旦开始推送 HTTP 就是 200，走到这里的 4xx/5xx 一定是真实失败
    throw toApiError(parsed, response.status)
  }

  if (responseType === 'text') {
    const text = await response.text()
    return text as unknown as T
  }

  let payload: ApiResponse<T>
  try {
    payload = (await response.json()) as ApiResponse<T>
  } catch {
    throw new ApiError('响应解析失败：后端返回的不是合法 JSON', {
      kind: 'business',
      code: ResultCode.INTERNAL_ERROR,
      httpStatus: response.status,
    })
  }

  if (payload.code !== ResultCode.SUCCESS) {
    throw new ApiError(payload.message || '请求失败', {
      code: payload.code,
      httpStatus: response.status,
      kind: 'business',
      detail: payload.message,
    })
  }

  return payload.data
}

export const http = {
  get: <T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    request<T>(path, { ...options, method: 'GET' }),
  post: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) =>
    request<T>(path, { ...options, method: 'POST', body }),
}

/** 页面用的错误文案：超时、网络、业务三类互不相同 */
export function describeError(error: unknown): string {
  if (isApiError(error)) {
    switch (error.kind) {
      case 'timeout':
        return error.message
      case 'network':
        return '连不上后端服务，确认后端已启动（默认 http://localhost:8080）'
      case 'canceled':
        return '请求已取消'
      case 'business':
        return error.detail || error.message
      default:
        return error.message
    }
  }
  return error instanceof Error ? error.message : '未知错误'
}

export { BASE_URL, TIMEOUT_MS }
