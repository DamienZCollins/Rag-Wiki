import type { PageStatus } from '@/api/wiki'

/** 条目树的状态筛选。ALL 表示不筛选，切换只影响本地列表，不触发请求 */
export type PageFilter = 'ALL' | PageStatus

export interface FilterOption {
  value: PageFilter
  label: string
}

export const PAGE_FILTERS: FilterOption[] = [
  { value: 'ALL', label: '全部' },
  { value: 'ACTIVE', label: '生效' },
  { value: 'RETIRED', label: '已失效' },
]
