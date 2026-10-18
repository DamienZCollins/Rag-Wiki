import { computed, ref, shallowRef } from 'vue'
import { listPages, type PageStatus, type WikiPageSummary } from '@/api/wiki'

/**
 * 条目目录。
 *
 * 后端一次返回全量，状态筛选在本地做，因此目录放模块级缓存：
 * 切换筛选、在条目之间跳转都不会重新请求；只有显式刷新才回源。
 */

const pages = shallowRef<WikiPageSummary[]>([])
const loading = ref(false)
const loaded = ref(false)
const error = ref<unknown>(null)

let inflight: Promise<void> | null = null

const collator = new Intl.Collator('zh-Hans-CN')

function sortByTitle(list: WikiPageSummary[]): WikiPageSummary[] {
  return [...list].sort((left, right) => collator.compare(left.title, right.title))
}

export function usePageCatalog() {
  const counts = computed<Record<PageStatus | 'ALL', number>>(() => {
    const result: Record<PageStatus | 'ALL', number> = {
      ALL: pages.value.length,
      ACTIVE: 0,
      RETIRED: 0,
      DRAFT: 0,
    }
    for (const page of pages.value) {
      result[page.status] += 1
    }
    return result
  })

  /** 首次进入加载；目录已在内存时直接返回，force 为真才回源 */
  function ensureLoaded(force = false): Promise<void> {
    if (inflight) return inflight
    if (loaded.value && !force) return Promise.resolve()

    loading.value = true
    error.value = null
    inflight = listPages('ALL')
      .then((list) => {
        pages.value = sortByTitle(list)
        loaded.value = true
      })
      .catch((cause: unknown) => {
        error.value = cause
      })
      .finally(() => {
        loading.value = false
        inflight = null
      })
    return inflight
  }

  return { pages, loading, loaded, error, counts, ensureLoaded }
}
