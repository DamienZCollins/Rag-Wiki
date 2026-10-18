import type { FinishReason } from '@/api/chat'
import type { SearchResponse } from '@/api/rag'

/**
 * 售后对话的数据模型。
 *
 * 一条助手消息承载一轮的全部产出：正文、工具过程、引用列表、检索侧状态。
 * 状态机（useChatSession）只改这些字段，组件只读它们。
 */

export type ChatChannel = 'fastpath' | 'rag'

/** 引用片段。检索侧只给元信息，两路原始分由 /api/rag/search 复算回填 */
export interface CitationRef {
  /** 正文里的 [n] 编号，1 起 */
  index: number
  chunkId: string
  title: string
  docId: string
  /** Wiki 条目标识；索引源为原始文档时为空 */
  pageId: string | null
  headingPath: string
  version: string | null
  sourceDocs: string[]
  content: string
  bm25Score: number | null
  bm25Rank: number | null
  vectorScore: number | null
  vectorRank: number | null
  rrfScore: number | null
  /** 分数是否已由检索接口补齐 */
  enriched: boolean
}

/**
 * 一次 search_knowledge 的结果列表。
 * 模型每轮只看得到最后一次检索的编号，所以解析 [n] 时按「最新列表优先」回溯。
 */
export interface CitationList {
  callId: string
  question: string
  items: CitationRef[]
}

export interface ToolTrace {
  callId: string
  name: string
  arguments: string
  status: 'running' | 'success' | 'error'
  content: string | null
  /** 到达时间，用于估算耗时 */
  startedAt: number
  endedAt: number | null
}

export type MessageRequest =
  | { kind: 'chat'; text: string }
  | { kind: 'fastpath'; entryId: string; question: string }

export interface StreamError {
  code: string
  message: string
}

/** 快通道直答的元信息。命中时整轮 token 为 0，这是「零模型调用」的佐证 */
export interface FastAnswerMeta {
  entryId: string | null
  question: string | null
  confidence: number
  source: string | null
  /** 从点击到答案渲染的毫秒数 */
  elapsedMs: number
}

/** 链路 Tab 的一步。只记关键节点，不记每一个 delta */
export interface TraceStep {
  label: string
  detail: string
  /** 相对本轮开始的毫秒数 */
  at: number
}

/** 一次检索接口复算的结果。一轮里可能因为改写问题而检索多次 */
export interface SearchRun {
  question: string
  response: SearchResponse | null
  error: string | null
  indexNotReady: boolean
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  text: string
  createdAt: number
  /** 由快通道未命中转来的检索，界面上标注「已转检索」 */
  routedFrom: 'fastpath' | null
  streaming: boolean
  thinking: boolean
  /** 判快通道的依据：整轮没出现过 THINKING 与 TOOL_CALL */
  channel: ChatChannel | null
  tools: ToolTrace[]
  citationLists: CitationList[]
  errors: StreamError[]
  finishReason: FinishReason | null
  tokenIn: number
  tokenOut: number
  /** 检索侧拒答：两路最高原始分都低于各自阈值 */
  refused: boolean
  /** 工具返回结构化 hit=false，检索侧确实没有依据 */
  toolMiss: boolean
  /** 本轮检索的完整返回，拒答卡片与右栏都用它 */
  search: SearchResponse | null
  /** 本轮每一次检索复算的结果，按发生顺序。是否拒答以最后一次有结果的为准 */
  searches: SearchRun[]
  /** 4001：索引未就绪，与拒答分开呈现 */
  indexNotReady: boolean
  /** 复算分数失败时的说明，只在抽屉与拒答卡里出现 */
  searchError: string | null
  disconnectReason: 'network' | 'closed' | null
  canceled: boolean
  /** 流内 ERROR(2002)：该会话有回合正在进行，不是普通错误 */
  busyConflict: boolean
  /** MAX_STEPS：建议转人工，不是错误 */
  handoffSuggested: boolean
  handoffDone: boolean
  fastAnswer: FastAnswerMeta | null
  events: TraceStep[]
  request: MessageRequest | null
}

export function emptyAssistant(id: string, request: MessageRequest | null = null): ChatMessage {
  return {
    id,
    role: 'assistant',
    text: '',
    createdAt: Date.now(),
    // 只有快通道确实未命中并转检索时才置 fastpath，命中的直答不标注
    routedFrom: null,
    streaming: false,
    thinking: false,
    channel: null,
    tools: [],
    citationLists: [],
    errors: [],
    finishReason: null,
    tokenIn: 0,
    tokenOut: 0,
    refused: false,
    toolMiss: false,
    search: null,
    searches: [],
    indexNotReady: false,
    searchError: null,
    disconnectReason: null,
    canceled: false,
    busyConflict: false,
    handoffSuggested: false,
    handoffDone: false,
    fastAnswer: null,
    events: [],
    request,
  }
}
