import { http } from './http'

/** 快通道条目。候选列表与命中结果同结构，字段见后端 FastEntryView */
export interface FastEntry {
  entryId: string
  scene: string
  question: string
  answer: string
  keywords: string[]
  confidence: number
  /** CANDIDATE / ACTIVE / OFFLINE */
  status: string
  /** DERIVED 由 Wiki 条目派生 / DISTILLED 由慢通道流量沉淀 */
  origin: string
  sourcePageId: string | null
  sourceTitle: string | null
  hitCount: number | null
}

/** 快通道作答结果。hit=false 时由调用方转 RAG */
export interface FastAnswer {
  hit: boolean
  entryId: string | null
  question: string | null
  answer: string | null
  confidence: number
  source: string | null
}

export function listEntries(scene = 'aftersale', signal?: AbortSignal): Promise<FastEntry[]> {
  return http.get<FastEntry[]>(`/api/fastpath/entries?scene=${encodeURIComponent(scene)}`, {
    signal,
  })
}

/** 传 entryId 表示点了候选；传 question 表示自由输入 */
export function answerByEntryId(entryId: string, signal?: AbortSignal): Promise<FastAnswer> {
  return http.post<FastAnswer>('/api/fastpath/answer', { entryId }, { signal })
}

export function answerByQuestion(question: string, signal?: AbortSignal): Promise<FastAnswer> {
  return http.post<FastAnswer>('/api/fastpath/answer', { question }, { signal })
}

/** 候选池，运维视图用 */
export function listCandidates(
  status: 'CANDIDATE' | 'ACTIVE' | 'OFFLINE' = 'CANDIDATE',
  signal?: AbortSignal,
): Promise<FastEntry[]> {
  return http.get<FastEntry[]>(
    `/api/fastpath/entries/candidates?status=${encodeURIComponent(status)}`,
    { signal },
  )
}
