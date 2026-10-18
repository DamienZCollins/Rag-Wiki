import { onBeforeUnmount, ref, shallowRef, type Ref, type ShallowRef } from 'vue'
import { describeError } from '@/api/http'
import { isApiError } from '@/api/types'
import type { OpsFailure, OpsOutcome, OpsPhase } from './types'

/**
 * 一次同步运维操作的状态机。
 *
 * 这些接口都是同步的：没有返回就是还没跑完，因此这里只记录真实的两段时间——
 * 页面等待时长（前端墙钟）和接口返回里的后端耗时，不做任何进度推算。
 */
export interface OpsAction<T> {
  phase: Ref<OpsPhase>
  elapsedMs: Ref<number>
  outcome: ShallowRef<OpsOutcome<T> | null>
  failure: ShallowRef<OpsFailure | null>
  run: (task: (signal: AbortSignal) => Promise<T>) => Promise<T | null>
  reset: () => void
}

export function toFailure(error: unknown): OpsFailure {
  if (isApiError(error)) {
    return {
      message: describeError(error),
      code: error.code,
      httpStatus: error.httpStatus,
      kind: error.kind,
    }
  }
  return {
    message: error instanceof Error ? error.message : String(error),
    code: null,
    httpStatus: null,
    kind: null,
  }
}

export function useOpsAction<T>(): OpsAction<T> {
  const phase = ref<OpsPhase>('idle')
  const elapsedMs = ref(0)
  const outcome = shallowRef<OpsOutcome<T> | null>(null)
  const failure = shallowRef<OpsFailure | null>(null)

  let ticker: ReturnType<typeof setInterval> | null = null
  let controller: AbortController | null = null
  let disposed = false

  function stopTicker() {
    if (ticker !== null) {
      clearInterval(ticker)
      ticker = null
    }
  }

  function reset() {
    stopTicker()
    controller?.abort()
    controller = null
    elapsedMs.value = 0
    phase.value = 'idle'
    outcome.value = null
    failure.value = null
  }

  async function run(task: (signal: AbortSignal) => Promise<T>): Promise<T | null> {
    // 进行中不重复发起：即使按钮被绕过点击，这里也拦住
    if (phase.value === 'running') return null

    const startedAt = Date.now()
    const localController = new AbortController()
    controller = localController
    phase.value = 'running'
    elapsedMs.value = 0
    outcome.value = null
    failure.value = null
    stopTicker()
    ticker = setInterval(() => {
      elapsedMs.value = Date.now() - startedAt
    }, 500)

    try {
      const data = await task(localController.signal)
      stopTicker()
      const waited = Date.now() - startedAt
      elapsedMs.value = waited
      if (!disposed) {
        outcome.value = { data, elapsedMs: waited, finishedAt: new Date().toISOString() }
        phase.value = 'success'
      }
      return data
    } catch (error) {
      stopTicker()
      elapsedMs.value = Date.now() - startedAt
      const failureInfo = toFailure(error)
      if (!disposed && failureInfo.kind !== 'canceled') {
        failure.value = failureInfo
        phase.value = 'error'
      }
      return null
    } finally {
      if (controller === localController) controller = null
    }
  }

  onBeforeUnmount(() => {
    disposed = true
    stopTicker()
    controller?.abort()
    controller = null
  })

  return { phase, elapsedMs, outcome, failure, run, reset }
}
