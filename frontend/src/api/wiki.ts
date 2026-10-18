import { http } from './http'

/** 条目状态。DRAFT 草稿 / ACTIVE 生效 / RETIRED 已失效 */
export type PageStatus = 'DRAFT' | 'ACTIVE' | 'RETIRED'

export interface WikiPageSummary {
  pageId: string
  title: string
  version: string
  status: PageStatus
  effectiveAt: string | null
  expiresAt: string | null
  sources: string[]
  updatedAt: string
  outboundCount: number
  inboundCount: number
}

/** 正文四段固定结构：摘要 / 适用条件 / 例外 / 关联条目 */
export interface PageContent {
  summary: string
  conditions: string[]
  exceptions: string[]
  relatedTitles: string[]
}

/** 一条双链。status 为 PENDING 表示目标条目还不存在 */
export interface WikiLink {
  pageId: string | null
  title: string
  status: 'ACTIVE' | 'PENDING'
}

export interface WikiPageDetail {
  summary: WikiPageSummary
  content: PageContent
  links: WikiLink[]
  backlinks: WikiLink[]
}

export type LintKind = 'CONFLICT' | 'EXPIRED' | 'ORPHAN' | 'PENDING_LINK'
export type LintSeverity = 'HIGH' | 'MEDIUM' | 'LOW'

export interface LintIssue {
  kind: LintKind
  severity: LintSeverity
  pageId: string
  title: string
  detail: string
  relatedPageId: string | null
  relatedTitle: string | null
}

export interface LintReport {
  pageCount: number
  conflicts: LintIssue[]
  expired: LintIssue[]
  orphans: LintIssue[]
  pendingLinks: LintIssue[]
}

export interface CompileRequest {
  sourceDocId?: string | null
  incremental?: boolean
}

export interface CompileReport {
  compiledDocs: string[]
  skippedDocs: string[]
  notes: string[]
  created: number
  updated: number
  retired: number
  failedDocs: string[]
  createdPages: string[]
  updatedPages: string[]
  retiredPages: string[]
  elapsedMillis: number
  modelCalls: number
  promptTokens: number
  completionTokens: number
}

export function listPages(
  status: 'ALL' | PageStatus = 'ALL',
  signal?: AbortSignal,
): Promise<WikiPageSummary[]> {
  return http.get<WikiPageSummary[]>(`/api/wiki/pages?status=${encodeURIComponent(status)}`, {
    signal,
  })
}

/** pageId 含中文，路径段必须整体编码 */
export function getPage(pageId: string, signal?: AbortSignal): Promise<WikiPageDetail> {
  return http.get<WikiPageDetail>(`/api/wiki/pages/${encodeURIComponent(pageId)}`, { signal })
}

export function runLint(signal?: AbortSignal): Promise<LintReport> {
  return http.post<LintReport>('/api/wiki/lint', undefined, { signal, timeoutMs: 5 * 60_000 })
}

export function compile(
  request: CompileRequest = {},
  signal?: AbortSignal,
): Promise<CompileReport> {
  return http.post<CompileReport>('/api/wiki/compile', request, {
    signal,
    timeoutMs: 30 * 60_000,
  })
}
