import { ref, shallowRef } from 'vue'
import { runLint, type LintReport } from '@/api/wiki'

/**
 * lint 结果。
 *
 * 体检要跑一遍模型，单次两分钟左右，所以结果放模块级缓存：
 * 在体检页与条目页之间来回切换不重复触发，只有点「重新体检」才回源。
 */

const report = shallowRef<LintReport | null>(null)
const running = ref(false)
const error = ref<unknown>(null)
const finishedAt = ref<number | null>(null)
const elapsedMs = ref<number | null>(null)

export function useLintReport() {
  /** 正在跑时重复调用直接复用同一个 Promise，不会打出两次请求 */
  async function refresh(): Promise<void> {
    if (running.value) return
    running.value = true
    error.value = null
    const startedAt = performance.now()

    try {
      report.value = await runLint()
      finishedAt.value = Date.now()
      elapsedMs.value = performance.now() - startedAt
    } catch (cause) {
      error.value = cause
    } finally {
      running.value = false
    }
  }

  function issueTotal(value: LintReport | null): number {
    if (!value) return 0
    return (
      value.conflicts.length +
      value.expired.length +
      value.orphans.length +
      value.pendingLinks.length
    )
  }

  return { report, running, error, finishedAt, elapsedMs, refresh, issueTotal }
}
