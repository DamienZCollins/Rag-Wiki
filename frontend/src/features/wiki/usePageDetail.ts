import { onScopeDispose, ref, shallowRef, watch, type Ref } from 'vue'
import { getPage, type WikiPageDetail } from '@/api/wiki'
import { isApiError } from '@/api/types'

/**
 * 单个条目详情。
 * 切换条目时中止上一个请求，并用序号丢弃过期响应，避免快速点选时旧数据覆盖新数据。
 */
export function usePageDetail(pageId: Ref<string>) {
  const detail = shallowRef<WikiPageDetail | null>(null)
  const loading = ref(false)
  const error = ref<unknown>(null)

  let controller: AbortController | null = null
  let seq = 0

  async function load(id: string) {
    controller?.abort()
    if (!id) {
      detail.value = null
      error.value = null
      loading.value = false
      return
    }

    const current = new AbortController()
    controller = current
    const ticket = ++seq
    loading.value = true
    error.value = null

    try {
      const data = await getPage(id, current.signal)
      if (ticket !== seq) return
      detail.value = data
    } catch (cause) {
      if (ticket !== seq) return
      if (isApiError(cause) && cause.kind === 'canceled') return
      error.value = cause
      detail.value = null
    } finally {
      if (ticket === seq) loading.value = false
    }
  }

  watch(pageId, (id) => void load(id), { immediate: true })
  onScopeDispose(() => controller?.abort())

  return {
    detail,
    loading,
    error,
    reload: () => load(pageId.value),
  }
}
