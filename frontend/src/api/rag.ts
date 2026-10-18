import { http } from './http'

/** 检索片段。字段与后端 SearchResponse.Chunk 一致 */
export interface Chunk {
  chunkId: string
  docId: string
  /** Wiki 条目标识，索引源是原始文档时为空 */
  pageId: string | null
  sourceDocs: string[]
  title: string
  headingPath: string
  content: string
  category: string | null
  version: string | null
  bm25Score: number | null
  bm25Rank: number | null
  vectorScore: number | null
  vectorRank: number | null
  rrfScore: number
}

/** 检索结果。refused=true 时 chunks 为空，两路最高原始分低于各自阈值 */
export interface SearchResponse {
  question: string
  refused: boolean
  topBm25Score: number
  topVectorScore: number
  chunks: Chunk[]
}

export function search(
  question: string,
  index?: string,
  signal?: AbortSignal,
): Promise<SearchResponse> {
  return http.post<SearchResponse>(
    '/api/rag/search',
    index ? { question, index } : { question },
    { signal, timeoutMs: 60_000 },
  )
}

export interface RebuildReport {
  index: string
  documents: number
  chunks: number
  elapsedMillis: number
}

export function rebuildIndex(signal?: AbortSignal): Promise<RebuildReport> {
  return http.post<RebuildReport>('/api/rag/index/rebuild', undefined, {
    signal,
    // 离线批处理：要重新切分并逐条向量化，10 秒默认超时不够
    timeoutMs: 30 * 60_000,
  })
}
