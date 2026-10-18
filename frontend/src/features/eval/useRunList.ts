/**
 * 批次列表的加载与默认选中。
 *
 * 后端按 startedAt 倒序返回最多 50 条，这里不再排序，只负责：
 * 1. 拉取列表
 * 2. 没指定 runId 时选最近**已完成**的一批（RUNNING / INTERRUPTED 没有完整指标，不进默认位）
 * 3. 指定了 runId 但列表里没有时报错，而不是静默回退到最近一批——
 *    静默回退会让人以为看的是 URL 里那个批次，是最容易误导人的一种失败
 */
import { computed, ref, type Ref } from 'vue'
import { listRuns, type EvalRun } from '@/api/eval'

export interface RunListState {
  runs: Ref<EvalRun[]>
  loading: Ref<boolean>
  error: Ref<unknown>
  reload: () => void
  /** 最近一批已完成的批次 */
  latestCompleted: Ref<EvalRun | null>
  /** URL 里指定但列表里不存在的 runId */
  missingRunId: Ref<string | null>
}

export function useRunList(requestedRunId: Ref<string | null>): RunListState {
  const runs = ref<EvalRun[]>([])
  const loading = ref(false)
  const error = ref<unknown>(null)
  let controller: AbortController | null = null

  async function load() {
    controller?.abort()
    const local = new AbortController()
    controller = local
    loading.value = true
    error.value = null
    try {
      const list = await listRuns(local.signal)
      if (local.signal.aborted) return
      runs.value = list
    } catch (cause) {
      if (local.signal.aborted) return
      error.value = cause
      runs.value = []
    } finally {
      if (!local.signal.aborted) loading.value = false
    }
  }

  void load()

  const latestCompleted = computed<EvalRun | null>(
    () => runs.value.find((run) => run.status === 'COMPLETED') ?? runs.value[0] ?? null,
  )

  const missingRunId = computed<string | null>(() => {
    const id = requestedRunId.value
    if (!id || loading.value || runs.value.length === 0) return null
    return runs.value.some((run) => run.runId === id) ? null : id
  })

  return { runs, loading, error, reload: load, latestCompleted, missingRunId }
}
