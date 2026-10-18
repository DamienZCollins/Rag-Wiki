import { http } from './http'

/** 归因层。与后端 Attributor.Layer 一一对应，五层不是四层 */
export type AttributionLayer = 'COMPILE' | 'RECALL' | 'RERANK' | 'GENERATION' | 'FALLBACK'

export const ATTRIBUTION_LAYERS: readonly AttributionLayer[] = [
  'COMPILE',
  'RECALL',
  'RERANK',
  'GENERATION',
  'FALLBACK',
]

export const ATTRIBUTION_LABEL: Record<AttributionLayer, string> = {
  COMPILE: '编译',
  RECALL: '召回',
  RERANK: '排序',
  GENERATION: '生成',
  FALLBACK: '兜底',
}

export type RunStatus = 'RUNNING' | 'COMPLETED' | 'INTERRUPTED'

/** 批次配置快照。跨批次比较前靠它核对口径 */
export interface RunConfig {
  alias: string
  aliasTarget: string
  indexSource: string
  indexVersion: string
  chunkMaxChars: number
  chunkOverlapChars: number
  candidateWindow: number
  topK: number
  rrfK: number
  refuseBm25Threshold: number
  refuseVectorThreshold: number
  fastPathEnabled: boolean
  fastPathMinConfidence: number
  agentMaxSteps: number
  chatModel: string
  embeddingModel: string
  embeddingDimensions: number
}

/** 指标。比率统一 4 位小数，口径见 docs/评测指标口径.md */
export interface EvalMetrics {
  caseCount: number
  passed: number
  failed: number
  passRate: number
  fastPathCount: number
  fastPathHitRate: number
  fastPathTokenIn: number
  fastPathTokenOut: number
  fastPathLatencyP50: number
  fastPathLatencyP99: number
  ragCount: number
  ragTokenInTotal: number
  ragTokenOutTotal: number
  ragTokenInMean: number
  ragTokenOutMean: number
  ragLatencyP50: number
  ragLatencyP95: number
  ragLatencyP99: number
  retrievalCaseCount: number
  recallRate: number
  mrr: number
  top1Rate: number
  refusalCaseCount: number
  refusalCorrectRate: number
  answerPointsTotal: number
  answerPointsHit: number
  answerPointRate: number
  forbiddenViolationCount: number
  citationViolationCount: number
  hallucinationCount: number
  hallucinationRate: number
}

export type Attribution = Partial<Record<AttributionLayer, number>>

export interface EvalRun {
  runId: string
  label: string
  caseTypes: string
  caseCount: number
  passed: number
  failed: number
  status: RunStatus
  latencyMs: number
  config: RunConfig
  metrics: EvalMetrics
  attribution: Attribution
  startedAt: string
}

/** 判定明细。字段与 EvalRunner.Result.detail() 一致 */
export interface CaseDetail {
  caseType?: string
  question?: string
  channel?: string
  finishReason?: string | null
  pointsTotal?: number
  pointsHit?: number
  pointsMissed?: string[]
  forbiddenHits?: string[]
  actualRefused?: boolean
  toolHit?: boolean
  toolArgMisses?: string[]
  citationOk?: boolean
  sourceMissing?: boolean
  missingSources?: string[]
  hasExpectedSource?: boolean
  expectedBestRank?: number
  probeRefused?: boolean
  probeTopBm25?: number
  probeTopVector?: number
  probeChunks?: { chunkId: string; title: string; docId: string }[]
}

export interface EvalFailure {
  caseId: string
  layer: AttributionLayer | 'NONE' | null
  channel: string | null
  error: string | null
  detail: CaseDetail
}

export interface EvalReport {
  run: EvalRun
  metrics: EvalMetrics
  attribution: Attribution
  failures: EvalFailure[]
}

export interface EvalCaseResult {
  caseId: string
  passed: boolean
  channel: string | null
  layer: AttributionLayer | 'NONE' | null
  latencyMs: number
  tokenIn: number | null
  tokenOut: number | null
  detail: CaseDetail
}

export interface GateComparison {
  metric: string
  baseline: number
  current: number
  delta: number
  regressed: boolean
}

export interface GateReport {
  passed: boolean
  baselineRunId: string
  tolerance: number
  comparisons: GateComparison[]
  regressed: string[]
  failedCases: string[]
}

export interface IndexStatus {
  aliasTarget: string | null
  physicalIndices: string[]
}

export interface CaseImportReport {
  imported: number
  byType: Record<string, number>
  files: string[]
}

export interface BaselineReport {
  runId: string
  label: string
  metrics: Record<string, number>
}

export function listRuns(signal?: AbortSignal): Promise<EvalRun[]> {
  return http.get<EvalRun[]>('/api/eval/runs', { signal })
}

export function getReport(runId: string, signal?: AbortSignal): Promise<EvalReport> {
  return http.get<EvalReport>(`/api/eval/report/${encodeURIComponent(runId)}`, { signal })
}

export function getResults(runId: string, signal?: AbortSignal): Promise<EvalCaseResult[]> {
  return http.get<EvalCaseResult[]>(`/api/eval/results/${encodeURIComponent(runId)}`, {
    signal,
  })
}

export function getGate(runId: string, signal?: AbortSignal): Promise<GateReport> {
  return http.get<GateReport>(`/api/eval/gate/${encodeURIComponent(runId)}`, { signal })
}

export function getIndexStatus(signal?: AbortSignal): Promise<IndexStatus> {
  return http.get<IndexStatus>('/api/eval/index/status', { signal })
}

export function switchIndex(physicalIndex: string, signal?: AbortSignal): Promise<IndexStatus> {
  return http.post<IndexStatus>(
    '/api/eval/index/switch',
    { physicalIndex },
    { signal, timeoutMs: 60_000 },
  )
}

export function importCases(signal?: AbortSignal): Promise<CaseImportReport> {
  return http.post<CaseImportReport>('/api/eval/cases/import', undefined, {
    signal,
    timeoutMs: 120_000,
  })
}

export interface RunRequest {
  label?: string | null
  caseTypes?: string[]
}

/** 跑批是同步接口，会阻塞到批次结束 */
export function runEval(request: RunRequest = {}, signal?: AbortSignal): Promise<EvalRun> {
  return http.post<EvalRun>('/api/eval/run', request, { signal, timeoutMs: 6 * 60 * 60_000 })
}

export function writeBaseline(runId: string, signal?: AbortSignal): Promise<BaselineReport> {
  return http.post<BaselineReport>(`/api/eval/baseline/${encodeURIComponent(runId)}`, undefined, {
    signal,
    timeoutMs: 120_000,
  })
}
