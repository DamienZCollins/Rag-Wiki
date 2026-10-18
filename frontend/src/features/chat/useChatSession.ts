import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { answerByEntryId, listEntries, type FastEntry } from '@/api/fastpath'
import { search } from '@/api/rag'
import type { ChatEvent } from '@/api/chat'
import { describeError } from '@/api/http'
import { isApiError, ResultCode, type ErrorPhase } from '@/api/types'
import { ChatStreamBusyError, streamChat } from '@/sse/chatStream'
import { useSessionStore } from '@/stores/session'
import { useToast } from '@/composables/useToast'
import { applyScores, isToolMiss, parseToolCitations, questionOf } from './citations'
import {
  emptyAssistant,
  type ChatMessage,
  type CitationRef,
  type MessageRequest,
  type SearchRun,
  type TraceStep,
} from './types'

/**
 * 单轮对话状态机。
 *
 * 事件到界面的映射集中在这里，组件只负责渲染：
 * STARTED 建轮次、THINKING 显式思考态、TEXT_DELTA 追加正文、TOOL_CALL/TOOL_RESULT 按 callId 配对、
 * FINISHED 四态收尾、ERROR 只在流内提示并继续等 FINISHED。
 *
 * 快通道与 RAG 共用同一套消息结构，靠「整轮有没有出现过 THINKING / TOOL_CALL」区分通道。
 */

const FASTPATH_PREVIEW_COUNT = 6

export type ContextTab = 'citations' | 'trace'

interface DrawerState {
  open: boolean
  messageId: string
  citation: CitationRef | null
  index: number
  /** 编号越界：本轮没有这个片段 */
  invalid: boolean
  /** 本轮检索到的最大编号 */
  maxIndex: number
  /** 复算分数失败时的说明 */
  scoreNote: string | null
}

export function useChatSession() {
  const route = useRoute()
  const router = useRouter()
  const sessionStore = useSessionStore()
  const toast = useToast()

  /** 每个会话各自的对话流，切换会话不串味 */
  const messagesBySession = new Map<string, ChatMessage[]>()
  const sessionId = ref('')
  const messages = ref<ChatMessage[]>([])

  const candidates = ref<FastEntry[]>([])
  const candidatesPhase = ref<ErrorPhase>('idle')
  const candidatesError = ref<string | null>(null)
  /** 候选区是否从「已收起」重新展开 */
  const candidatesOpen = ref(false)
  /** 是否铺开全部 68 条，默认只显示前 6 条 */
  const candidatesShowAll = ref(false)

  const controller = shallowRef<AbortController | null>(null)
  const streaming = ref(false)
  const contextTab = ref<ContextTab>('citations')
  const drawer = ref<DrawerState>({
    open: false,
    messageId: '',
    citation: null,
    index: 0,
    invalid: false,
    maxIndex: 0,
    scoreNote: null,
  })

  let turnSeq = 0
  /** 触发抽屉的角标，关闭后把焦点还回去 */
  let drawerTrigger: HTMLElement | null = null

  const hasMessages = computed(() => messages.value.length > 0)

  const lastAssistant = computed<ChatMessage | null>(() => {
    for (let i = messages.value.length - 1; i >= 0; i -= 1) {
      const item = messages.value[i]
      if (item.role === 'assistant') return item
    }
    return null
  })

  /** 本轮可用的引用：取最后一条助手消息里最近一次检索的列表 */
  const activeCitations = computed<CitationRef[]>(() => {
    const message = lastAssistant.value
    if (!message || message.citationLists.length === 0) return []
    return message.citationLists[message.citationLists.length - 1].items
  })

  const visibleCandidates = computed(() =>
    candidatesShowAll.value ? candidates.value : candidates.value.slice(0, FASTPATH_PREVIEW_COUNT),
  )

  /** 会话已有消息且未主动展开时，候选区收成一行提示，不遮挡对话流 */
  const candidatesCollapsed = computed(() => hasMessages.value && !candidatesOpen.value)

  const sessionSuffix = computed(() => sessionId.value.slice(-4) || '——')

  /** 从 URL 进入的会话视为「续聊」：历史气泡不回显，要如实说明 */
  const resumed = ref(false)

  // ---------- 会话 ----------

  function syncUrl(id: string) {
    if (route.params.sessionId === id) return
    void router.replace({ name: 'chat-session', params: { sessionId: id } })
  }

  function switchSession(id: string, fromRoute: boolean) {
    if (id === sessionId.value) return
    // 切换会话必须中止上一个流，否则两轮事件会写进同一份消息列表
    abortStream()
    closeDrawer()
    sessionId.value = id
    messages.value = messagesBySession.get(id) ?? []
    messagesBySession.set(id, messages.value)
    resumed.value = fromRoute && messages.value.length === 0
    sessionStore.touch(id)
    if (!fromRoute) syncUrl(id)
  }

  /** 首次进入生成 sessionId 并写入 URL；刷新时按 URL 里的 sessionId 续聊 */
  function resolveSession() {
    const fromRoute = route.params.sessionId
    if (typeof fromRoute === 'string' && fromRoute.length > 0) {
      switchSession(fromRoute, true)
      return
    }
    const id = sessionStore.newSessionId()
    sessionId.value = id
    messages.value = []
    messagesBySession.set(id, messages.value)
    resumed.value = false
    sessionStore.touch(id)
    syncUrl(id)
  }

  function newSession() {
    abortStream()
    closeDrawer()
    const id = sessionStore.newSessionId()
    switchSession(id, false)
    candidatesOpen.value = false
    candidatesShowAll.value = false
  }

  function selectSession(id: string) {
    switchSession(id, false)
  }

  function removeSession(id: string) {
    sessionStore.remove(id)
    messagesBySession.delete(id)
    if (id === sessionId.value) newSession()
  }

  function abortStream() {
    controller.value?.abort()
    controller.value = null
    streaming.value = false
  }

  // ---------- 候选 ----------

  async function loadCandidates() {
    candidatesPhase.value = 'loading'
    candidatesError.value = null
    try {
      candidates.value = await listEntries('aftersale')
      candidatesPhase.value = candidates.value.length > 0 ? 'success' : 'empty'
    } catch (error) {
      // 候选失败不阻塞对话区，只在这一块给提示
      candidatesPhase.value = 'error'
      candidatesError.value = describeError(error)
    }
  }

  function toggleCandidates() {
    // 从收起态点「重新展开」时展开；展开态点「收起」时回到收起
    candidatesOpen.value = !candidatesOpen.value
  }

  function toggleShowAll() {
    candidatesShowAll.value = !candidatesShowAll.value
  }

  // ---------- 轮次 ----------

  /**
   * 把新消息推进列表，并返回列表里的那个响应式代理。
   * 直接持有 push 进去的原始对象改字段不会触发更新，必须从数组里取回代理。
   */
  function pushMessage(raw: ChatMessage): ChatMessage {
    messages.value.push(raw)
    return messages.value[messages.value.length - 1]
  }

  function pushUserMessage(text: string): ChatMessage {
    const message: ChatMessage = {
      ...emptyAssistant(`u-${++turnSeq}`),
      role: 'user',
      text,
    }
    return pushMessage(message)
  }

  function note(message: ChatMessage, label: string, detail: string, startedAt: number) {
    const step: TraceStep = { label, detail, at: Date.now() - startedAt }
    message.events.push(step)
  }

  /**
   * 用检索接口复算同一个问题，补齐两路原始分与拒答信息。
   *
   * Agent 会改写问题，一轮里可能检索多次（实测一次提问出现 3 次 search_knowledge）。
   * 每次复算都记进 searches，是否拒答以「最后一次有结果的检索」为准：
   * 前一次拒答、后一次改写到命中，就不该给用户看拒答卡片。
   * 每轮每个问题只算一次；失败静默降级，绝不影响主流程。
   */
  async function enrich(
    message: ChatMessage,
    question: string,
    done: Set<string>,
    signal: AbortSignal,
  ) {
    if (done.has(question)) return
    done.add(question)
    const run: SearchRun = { question, response: null, error: null, indexNotReady: false }
    message.searches.push(run)
    try {
      const response = await search(question, undefined, signal)
      run.response = response
      applyScores(message.citationLists, response)
    } catch (error) {
      if (signal.aborted) return
      if (isApiError(error) && error.code === ResultCode.RAG_INDEX_EMPTY) {
        // 4001 是索引问题，不能展示成拒答
        run.indexNotReady = true
      } else {
        run.error = describeError(error)
      }
    }
    syncSearchState(message)
  }

  /**
   * 由 searches 推导出界面用的聚合状态。
   *
   * 以「最后一次有结果的检索」为准；但拒答还要看本轮有没有真的拿到片段：
   * Agent 会改写问题并多次检索，某一次复算可能被拒答，而工具已经把别的片段交给模型作答。
   * 只要本轮存在可引用片段，就不算拒答，否则界面会一边给引用一边说没有依据。
   */
  function syncSearchState(message: ChatMessage) {
    const lastOk = [...message.searches].reverse().find((run) => run.response !== null)
    const lastIndexNotReady = [...message.searches].reverse().find((run) => run.indexNotReady)
    const lastError = [...message.searches].reverse().find((run) => run.error !== null)
    const citationCount = message.citationLists.reduce((total, list) => total + list.items.length, 0)

    message.search = lastOk?.response ?? null
    message.refused = Boolean(lastOk?.response?.refused) && citationCount === 0
    // 一次都没成功才暴露索引未就绪 / 检索报错
    message.indexNotReady = !lastOk && Boolean(lastIndexNotReady)
    message.searchError = !lastOk && !lastIndexNotReady ? (lastError?.error ?? null) : null
  }

  /** 工具结果落库后引用数量会变，重新聚合一次 */
  function refreshSearchState(message: ChatMessage) {
    if (message.searches.length > 0) syncSearchState(message)
  }

  /** 发一轮 /api/chat */
  async function sendChat(request: MessageRequest, assistant: ChatMessage) {
    const abort = new AbortController()
    controller.value = abort
    streaming.value = true
    assistant.streaming = true
    assistant.request = request
    const startedAt = Date.now()
    const enrichDone = new Set<string>()
    const enrichAbort = new AbortController()
    const onAbort = () => enrichAbort.abort()
    abort.signal.addEventListener('abort', onAbort, { once: true })

    const promptText = request.kind === 'chat' ? request.text : request.question

    const onEvent = (event: ChatEvent) => {
      if (abort.signal.aborted) return
      switch (event.type) {
        case 'STARTED': {
          note(assistant, 'STARTED', `sessionId · ${sessionId.value}`, startedAt)
          break
        }
        case 'THINKING': {
          if (!assistant.thinking) {
            note(assistant, 'THINKING', '已发出模型请求，等待首个分片', startedAt)
          }
          assistant.thinking = true
          assistant.channel = 'rag'
          break
        }
        case 'TEXT_DELTA': {
          const first = assistant.text.length === 0
          assistant.text += event.text
          assistant.thinking = false
          if (first) {
            note(assistant, 'TEXT_DELTA', '开始流式输出正文', startedAt)
          }
          break
        }
        case 'TOOL_CALL': {
          assistant.thinking = false
          assistant.channel = 'rag'
          assistant.tools.push({
            callId: event.callId,
            name: event.name,
            arguments: event.arguments,
            status: 'running',
            content: null,
            startedAt: Date.now(),
            endedAt: null,
          })
          note(assistant, `TOOL_CALL · ${event.name}`, event.arguments, startedAt)
          break
        }
        case 'TOOL_RESULT': {
          const tool = assistant.tools.find((item) => item.callId === event.callId)
          if (tool) {
            tool.status = event.success ? 'success' : 'error'
            tool.content = event.content
            tool.endedAt = Date.now()
          }
          note(
            assistant,
            `TOOL_RESULT · ${event.name}`,
            event.success ? 'success' : 'error',
            startedAt,
          )

          if (event.name === 'search_knowledge') {
            const question = questionOf(tool?.arguments ?? '')
            if (isToolMiss(event.content)) {
              assistant.toolMiss = true
            } else if (event.success) {
              assistant.citationLists.push(
                parseToolCitations(event.callId, question, event.content),
              )
              // 新到的片段可能推翻之前那次的拒答判定
              refreshSearchState(assistant)
            }
            // 复算分数：拒答时也要调，卡片里的两路最高分只能从这里来
            void enrich(assistant, question, enrichDone, enrichAbort.signal)
          }
          break
        }
        case 'ERROR': {
          note(assistant, `ERROR · ${event.code}`, event.message, startedAt)
          if (event.code === '2002') {
            // 会话繁忙不是普通错误，等 FINISHED 后单独呈现
            assistant.busyConflict = true
            break
          }
          assistant.errors.push({ code: event.code, message: event.message })
          break
        }
        case 'FINISHED': {
          assistant.finishReason = event.reason
          assistant.tokenIn = event.tokenIn
          assistant.tokenOut = event.tokenOut
          assistant.thinking = false
          assistant.streaming = false
          note(
            assistant,
            `FINISHED · ${event.reason}`,
            `tokenIn ${event.tokenIn} · tokenOut ${event.tokenOut}`,
            startedAt,
          )
          if (event.reason === 'MAX_STEPS') {
            assistant.handoffSuggested = true
          }
          if (assistant.channel === null) {
            // 全程没有 THINKING、没有 TOOL_CALL：走的是快通道
            assistant.channel = 'fastpath'
          }
          break
        }
        default:
          break
      }
    }

    try {
      await streamChat(
        { sessionId: sessionId.value, message: promptText },
        {
          onEvent,
          onDisconnect: (reason) => {
            if (abort.signal.aborted) return
            assistant.disconnectReason = reason
            assistant.streaming = false
            assistant.thinking = false
          },
          onParseError: (raw) => {
            console.warn('[chat] 事件解析失败，已跳过', raw)
          },
        },
        abort.signal,
      )
    } catch (error) {
      if (!abort.signal.aborted) {
        if (error instanceof ChatStreamBusyError && error.status === 409) {
          assistant.busyConflict = true
        } else {
          assistant.errors.push({
            code: error instanceof ChatStreamBusyError ? String(error.code) : 'STREAM',
            message: describeError(error),
          })
        }
      }
    } finally {
      abort.signal.removeEventListener('abort', onAbort)
      if (controller.value === abort) {
        controller.value = null
        streaming.value = false
      }
      assistant.streaming = false
      assistant.thinking = false
      if (abort.signal.aborted && assistant.finishReason === null) {
        // 用户主动停止：后端不会补 FINISHED
        assistant.canceled = true
        note(assistant, 'ABORTED', '用户停止生成', startedAt)
      }
    }
  }

  /** 发一句话：先落用户气泡，再开一轮 */
  function submitChat(text: string, routedFrom: 'fastpath' | null = null) {
    const content = text.trim()
    if (!content || streaming.value) return
    pushUserMessage(content)
    sessionStore.touch(sessionId.value, content)
    const assistant = pushMessage(emptyAssistant(`a-${++turnSeq}`))
    assistant.routedFrom = routedFrom
    void sendChat({ kind: 'chat', text: content }, assistant)
  }

  /** 点候选：先问快通道，命中直答；未命中自动转一次 RAG */
  async function submitCandidate(entry: FastEntry) {
    if (streaming.value) return
    pushUserMessage(entry.question)
    sessionStore.touch(sessionId.value, entry.question)

    const startedAt = Date.now()
    // 取回列表里的响应式代理，直接改 push 进去的原始对象不会触发更新
    const assistant = pushMessage(emptyAssistant(`a-${++turnSeq}`))
    assistant.streaming = true

    try {
      const answer = await answerByEntryId(entry.entryId)
      if (answer.hit && answer.answer) {
        assistant.channel = 'fastpath'
        assistant.text = answer.answer
        assistant.finishReason = 'COMPLETED'
        assistant.tokenIn = 0
        assistant.tokenOut = 0
        assistant.fastAnswer = {
          entryId: answer.entryId,
          question: answer.question,
          confidence: answer.confidence,
          source: answer.source,
          elapsedMs: Date.now() - startedAt,
        }
        note(assistant, 'FASTPATH', `命中 ${answer.entryId ?? entry.entryId}`, startedAt)
        note(assistant, 'FINISHED · COMPLETED', '快通道直答，零模型调用', startedAt)
        assistant.streaming = false
        return
      }
      // 未命中：自动降级到 RAG，用户无感，但界面标注「已转检索」
      assistant.streaming = false
      assistant.routedFrom = 'fastpath'
      note(assistant, 'FASTPATH_MISS', '快通道未命中，转检索', startedAt)
      toast.info('快通道未命中，已转检索')
      void sendChat({ kind: 'chat', text: entry.question }, assistant)
    } catch (error) {
      assistant.streaming = false
      assistant.errors.push({ code: 'FASTPATH', message: describeError(error) })
    }
  }

  /** 重试：按上一轮的请求重发 */
  function retry(message: ChatMessage) {
    if (streaming.value) return
    if (message.request?.kind === 'chat') {
      submitChat(message.request.text)
    }
  }

  /** 检索失败后重试检索：重开一轮 RAG 对话 */
  function retrySearch(message: ChatMessage) {
    if (streaming.value) return
    const text = message.request?.kind === 'chat' ? message.request.text : ''
    if (text) submitChat(text)
  }

  function stop() {
    if (!streaming.value) return
    controller.value?.abort()
  }

  // ---------- 引用抽屉 ----------

  function openCitation(messageId: string, index: number, event: MouseEvent) {
    const message = messages.value.find((item) => item.id === messageId)
    let citation: CitationRef | null = null
    let maxIndex = 0
    if (message) {
      // 后一次检索覆盖前一次的同号片段
      for (let i = message.citationLists.length - 1; i >= 0 && !citation; i -= 1) {
        const list = message.citationLists[i]
        for (const item of list.items) {
          if (item.index === index) {
            citation = item
            break
          }
          if (item.index > maxIndex) maxIndex = item.index
        }
      }
      for (const list of message.citationLists) {
        for (const item of list.items) {
          if (item.index > maxIndex) maxIndex = item.index
        }
      }
    }
    drawerTrigger = (event.currentTarget as HTMLElement | null) ?? null
    drawer.value = {
      open: true,
      messageId,
      citation,
      index,
      invalid: citation === null,
      maxIndex,
      scoreNote: message?.searchError ?? null,
    }
  }

  function closeDrawer() {
    if (!drawer.value.open) return
    drawer.value = { ...drawer.value, open: false }
    const trigger = drawerTrigger
    drawerTrigger = null
    // 焦点还给触发角标
    if (trigger?.isConnected) {
      requestAnimationFrame(() => trigger.focus())
    }
  }

  // ---------- 转人工 ----------

  /** 后端没有工单接口：只把本地会话标为已转人工并提示，不伪造工单号与坐席 */
  function handoff(message?: ChatMessage | null) {
    sessionStore.markHandoff(sessionId.value)
    if (message) message.handoffDone = true
    toast.success('已记录：本会话标记为已转人工')
  }

  // ---------- 生命周期 ----------

  watch(
    () => route.params.sessionId,
    (value) => {
      if (typeof value === 'string' && value.length > 0) {
        switchSession(value, true)
      }
    },
  )

  onBeforeUnmount(() => {
    abortStream()
    closeDrawer()
  })

  function bootstrap() {
    resolveSession()
    void loadCandidates()
  }

  return {
    // 状态
    sessionId,
    sessionSuffix,
    messages,
    hasMessages,
    lastAssistant,
    activeCitations,
    resumed,
    streaming,
    candidates,
    candidatesPhase,
    candidatesError,
    candidatesOpen,
    candidatesShowAll,
    candidatesCollapsed,
    visibleCandidates,
    contextTab,
    drawer,
    // 动作
    bootstrap,
    loadCandidates,
    toggleCandidates,
    toggleShowAll,
    submitChat,
    submitCandidate,
    retry,
    retrySearch,
    stop,
    openCitation,
    closeDrawer,
    handoff,
    newSession,
    selectSession,
    removeSession,
  }
}
