/**
 * 评测数据的取数与缓存。
 *
 * 一个批次的数据来自三个接口，切换批次时一并刷新：
 * - `getReport`  → 批次信息、指标、归因分布、失败清单（含链路报错 error）
 * - `getResults` → 逐条结果，含耗时与 token（失败列表的耗时只能从这里拿）
 * - `getGate`    → 回归门禁，可能因基线文件缺失而失败，失败不连坐其余面板
 *
 * 已完成的批次数据不会再变，因此按 runId 缓存，报告页跳到明细页不重复请求；
 * 只保留最近 3 个批次，避免 100+ 条明细在内存里堆积。
 */
import { ref, watch, type Ref } from 'vue'
import {
  getGate,
  getReport,
  getResults,
  type AttributionLayer,
  type CaseDetail,
  type EvalCaseResult,
  type EvalFailure,
  type EvalReport,
  type GateReport,
} from '@/api/eval'

export interface RunBundle {
  report: EvalReport
  results: EvalCaseResult[]
  gate: GateReport | null
  /** 门禁单独失败时的错误，面板内展示，不影响其余面板 */
  gateError: unknown
}

const CACHE_LIMIT = 3
const cache = new Map<string, RunBundle>()

function putCache(runId: string, bundle: RunBundle) {
  cache.delete(runId)
  cache.set(runId, bundle)
  while (cache.size > CACHE_LIMIT) {
    const oldest = cache.keys().next()
    if (oldest.done) break
    cache.delete(oldest.value)
  }
}

export function clearRunCache(runId?: string) {
  if (runId) cache.delete(runId)
  else cache.clear()
}

async function fetchBundle(runId: string, signal: AbortSignal): Promise<RunBundle> {
  const [report, results, gate] = await Promise.all([
    getReport(runId, signal),
    getResults(runId, signal),
    getGate(runId, signal).then(
      (value) => ({ ok: true as const, value }),
      (error: unknown) => ({ ok: false as const, error }),
    ),
  ])
  return {
    report,
    results,
    gate: gate.ok ? gate.value : null,
    gateError: gate.ok ? null : gate.error,
  }
}

export interface EvalBundleState {
  bundle: Ref<RunBundle | null>
  loading: Ref<boolean>
  error: Ref<unknown>
  reload: () => void
}

/**
 * 跟随 runId 加载批次数据。runId 为空时不发请求。
 * 连续切换批次时旧请求会被 abort，避免后到的旧数据覆盖新数据。
 */
export function useEvalBundle(runId: Ref<string | null>): EvalBundleState {
  const bundle = ref<RunBundle | null>(null)
  const loading = ref(false)
  const error = ref<unknown>(null)
  let controller: AbortController | null = null
  let generation = 0

  async function run(id: string | null) {
    controller?.abort()
    controller = null
    const mine = ++generation

    if (!id) {
      bundle.value = null
      loading.value = false
      error.value = null
      return
    }

    const cached = cache.get(id)
    if (cached) {
      bundle.value = cached
      loading.value = false
      error.value = null
      return
    }

    const local = new AbortController()
    controller = local
    loading.value = true
    error.value = null
    try {
      const next = await fetchBundle(id, local.signal)
      if (mine !== generation || local.signal.aborted) return
      putCache(id, next)
      bundle.value = next
    } catch (cause) {
      if (mine !== generation || local.signal.aborted) return
      bundle.value = null
      error.value = cause
    } finally {
      if (mine === generation) loading.value = false
    }
  }

  watch(runId, (id) => void run(id), { immediate: true })

  return {
    bundle,
    loading,
    error,
    reload: () => {
      if (runId.value) cache.delete(runId.value)
      void run(runId.value)
    },
  }
}

/** 失败行。耗时与 token 取自 results，链路报错取自 report.failures */
export interface FailureRow {
  caseId: string
  layer: AttributionLayer | 'NONE'
  channel: string | null
  latencyMs: number
  tokenIn: number | null
  tokenOut: number | null
  error: string | null
  detail: CaseDetail
  question: string
}

/**
 * 合并失败清单与逐条结果。
 * 以 report.failures 为准（后端就是这么筛的），再从 results 里补耗时与 token；
 * 万一某条 caseId 在 results 里不存在，保留该失败行、耗时按 0 处理，不静默丢行。
 */
export function mergeFailures(failures: EvalFailure[], results: EvalCaseResult[]): FailureRow[] {
  const byCaseId = new Map<string, EvalCaseResult>()
  for (const result of results) byCaseId.set(result.caseId, result)

  return failures.map((failure) => {
    const result = byCaseId.get(failure.caseId)
    const layer: AttributionLayer | 'NONE' =
      failure.layer === 'NONE' || failure.layer == null ? 'NONE' : failure.layer
    const detail = failure.detail ?? result?.detail ?? {}
    return {
      caseId: failure.caseId,
      layer,
      channel: failure.channel ?? result?.channel ?? null,
      latencyMs: result?.latencyMs ?? 0,
      tokenIn: result?.tokenIn ?? null,
      tokenOut: result?.tokenOut ?? null,
      error: failure.error ?? null,
      detail,
      question: detail.question ?? '',
    }
  })
}

/** 未归因条数：失败清单里 layer 为 NONE 的条数 */
export function countUnattributed(failures: EvalFailure[]): number {
  return failures.filter((failure) => failure.layer === 'NONE' || failure.layer == null).length
}
