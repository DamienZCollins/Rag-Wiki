/**
 * 失败用例的筛选与排序。
 *
 * 100+ 条失败也不卡的关键：筛选与排序全部走 computed 缓存，
 * 只有 `visible` 的结果进入渲染；展开状态用 Set 保存，切换筛选不重建整表。
 */
import { computed, ref, type Ref } from 'vue'
import { LAYER_ORDER, layerRank, type LayerKey } from './layers'
import type { FailureRow } from './useEvalBundle'

export type LayerFilter = LayerKey | 'ALL'

export interface FailureFilterState {
  layer: Ref<LayerFilter>
  keyword: Ref<string>
  /** 排序后的筛选结果 */
  visible: Ref<FailureRow[]>
  /** 各层条数（按当前关键词统计，未按层过滤），用于筛选条上的计数 */
  layerCounts: Ref<Record<string, number>>
  /** 关键词过滤后的总数 */
  matchedTotal: Ref<number>
  setLayer: (layer: LayerFilter) => void
  clear: () => void
}

/**
 * @param rows 已按 caseId 排序的失败清单
 * @param initialLayer 初始筛选层（明细页从 URL 带入）
 */
export function useFailureFilter(
  rows: Ref<FailureRow[]>,
  initialLayer: LayerFilter = 'ALL',
): FailureFilterState {
  const layer = ref<LayerFilter>(initialLayer)
  const keyword = ref('')

  /** 关键词命中：caseId 与问题文本都参与匹配 */
  const byKeyword = computed(() => {
    const query = keyword.value.trim().toLowerCase()
    if (!query) return rows.value
    return rows.value.filter(
      (row) =>
        row.caseId.toLowerCase().includes(query) || row.question.toLowerCase().includes(query),
    )
  })

  const layerCounts = computed(() => {
    const counts: Record<string, number> = { ALL: byKeyword.value.length }
    for (const row of byKeyword.value) {
      counts[row.layer] = (counts[row.layer] ?? 0) + 1
    }
    return counts
  })

  const visible = computed(() => {
    const filtered =
      layer.value === 'ALL'
        ? byKeyword.value
        : byKeyword.value.filter((row) => row.layer === layer.value)
    // 按归因层排序（编译 → 召回 → 排序 → 生成 → 兜底 → 未归因），同层按 caseId
    return [...filtered].sort((a, b) => {
      const layerDiff = layerRank(a.layer) - layerRank(b.layer)
      if (layerDiff !== 0) return layerDiff
      return a.caseId.localeCompare(b.caseId)
    })
  })

  return {
    layer,
    keyword,
    visible,
    layerCounts,
    matchedTotal: computed(() => byKeyword.value.length),
    setLayer: (next) => {
      layer.value = next
    },
    clear: () => {
      layer.value = 'ALL'
      keyword.value = ''
    },
  }
}

/** 层筛选条的顺序：全部 + 五层 + 未归因（仅当存在时） */
export function filterOptions(
  counts: Record<string, number>,
): { key: LayerFilter; count: number }[] {
  const options: { key: LayerFilter; count: number }[] = [{ key: 'ALL', count: counts.ALL ?? 0 }]
  for (const item of LAYER_ORDER) {
    options.push({ key: item, count: counts[item] ?? 0 })
  }
  if ((counts.NONE ?? 0) > 0) {
    options.push({ key: 'NONE', count: counts.NONE ?? 0 })
  }
  return options
}

export { LAYER_ORDER }
