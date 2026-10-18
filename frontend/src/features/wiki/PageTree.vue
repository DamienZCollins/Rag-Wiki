<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import SkeletonBlock from '@/components/SkeletonBlock.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import type { PageStatus, WikiPageSummary } from '@/api/wiki'
import { PAGE_FILTERS, type PageFilter } from './types'

/**
 * 左侧条目树。后端一次给全量，筛选在本地做，切换筛选不发请求。
 * 按状态分组：生效 / 草稿 / 已失效，组内按标题排序。
 */

const props = defineProps<{
  pages: WikiPageSummary[]
  currentId: string
  filter: PageFilter
  counts: Record<PageFilter, number>
  loading: boolean
  error: unknown
}>()

const emit = defineEmits<{
  'update:filter': [value: PageFilter]
  retry: []
}>()

const FILTERS = PAGE_FILTERS

const GROUP_ORDER: { status: PageStatus; label: string }[] = [
  { status: 'ACTIVE', label: '生效' },
  { status: 'DRAFT', label: '草稿' },
  { status: 'RETIRED', label: '已失效' },
]

const treeRef = ref<HTMLElement | null>(null)

const visiblePages = computed(() =>
  props.filter === 'ALL' ? props.pages : props.pages.filter((page) => page.status === props.filter),
)

const groups = computed(() =>
  GROUP_ORDER.map((group) => ({
    ...group,
    pages: visiblePages.value.filter((page) => page.status === group.status),
  })).filter((group) => group.pages.length > 0),
)

const totalLabel = computed(() => {
  const counts = props.counts
  const parts = [`共 ${counts.ALL} 条`, `生效 ${counts.ACTIVE}`]
  if (counts.DRAFT > 0) parts.push(`草稿 ${counts.DRAFT}`)
  parts.push(`已失效 ${counts.RETIRED}`)
  return parts.join(' · ')
})

/** 当前条目滚入视野：进入页面、切换条目、目录加载完成时各触发一次 */
function revealCurrent() {
  const container = treeRef.value
  const target = container?.querySelector<HTMLElement>('[aria-current="page"]')
  if (!container || !target) return

  const containerRect = container.getBoundingClientRect()
  const targetRect = target.getBoundingClientRect()
  // 浏览器在路由切换时会把页面整体滚到顶部，这里只调整树自己的滚动位置
  if (targetRect.top < containerRect.top) {
    container.scrollTop -= containerRect.top - targetRect.top + 8
  } else if (targetRect.bottom > containerRect.bottom) {
    container.scrollTop += targetRect.bottom - containerRect.bottom + 8
  }
}

watch(
  [() => props.currentId, () => props.loading, () => props.filter],
  () => {
    void nextTick(revealCurrent)
  },
  { immediate: true },
)
</script>

<template>
  <aside class="rail" aria-label="条目目录">
    <div class="rail-head">
      <div class="rail-title-row">
        <h2 class="rail-title">知识目录</h2>
        <span class="mono rail-count">{{ counts.ALL }}</span>
      </div>
      <div class="rail-sub">{{ totalLabel }}</div>
      <div class="seg" role="group" aria-label="按条目状态筛选">
        <button
          v-for="item in FILTERS"
          :key="item.value"
          type="button"
          :aria-pressed="filter === item.value"
          @click="emit('update:filter', item.value)"
        >
          {{ item.label }}
          <span class="seg-num">{{ counts[item.value] }}</span>
        </button>
      </div>
    </div>

    <div ref="treeRef" class="wiki-tree">
      <template v-if="loading && pages.length === 0">
        <div class="tree-skeleton">
          <SkeletonBlock :lines="9" height="13px" />
        </div>
      </template>

      <ErrorState
        v-else-if="error"
        :error="error"
        compact
        retry-text="重新加载"
        @retry="emit('retry')"
      />

      <EmptyState
        v-else-if="visiblePages.length === 0"
        compact
        :title="pages.length === 0 ? '还没有条目' : '该状态下没有条目'"
        :description="
          pages.length === 0
            ? '后端 Wiki 尚未编译出条目，先到运维台执行编译。'
            : '换一个筛选条件看看。'
        "
      />

      <details v-for="group in groups" :key="group.status" class="tree-group" open>
        <summary>
          <AppIcon name="chev" :size="12" :stroke-width="2" />
          <span class="group-label">{{ group.label }}</span>
          <span class="mono group-count">{{ group.pages.length }}</span>
        </summary>
        <RouterLink
          v-for="page in group.pages"
          :key="page.pageId"
          class="tree-item"
          :to="{ name: 'wiki-page', params: { pageId: page.pageId } }"
          :aria-current="page.pageId === currentId ? 'page' : undefined"
          :title="`${page.title}（${page.version}）`"
        >
          <span class="tree-title">{{ page.title }}</span>
          <StatusBadge :status="page.status" />
        </RouterLink>
      </details>
    </div>

    <div class="rail-foot">
      <span>{{ visiblePages.length }} 条在列表中</span>
      <span class="mono">本地筛选</span>
    </div>
  </aside>
</template>

<style scoped>
.rail {
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--paper);
  border-right: 1px solid var(--line);
}

.rail-head {
  padding: 14px 16px 10px;
}

.rail-title-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.rail-title {
  font-family: var(--serif);
  font-size: 16px;
  font-weight: 700;
  margin: 0;
}

.rail-count {
  font-size: 11px;
  color: var(--ink-faint);
}

.rail-sub {
  font-size: 11px;
  color: var(--ink-mute);
  margin-top: 3px;
  line-height: 1.6;
}

.seg button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}

.seg-num {
  font-family: var(--mono);
  font-size: 10px;
  color: var(--ink-faint);
}

.seg button[aria-pressed='true'] {
  background: var(--paper-raised);
  color: var(--ink);
  font-weight: 600;
  box-shadow: inset 0 0 0 1px var(--line);
}

.seg button[aria-pressed='true'] .seg-num {
  color: var(--ink-mute);
}

.wiki-tree {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 12px 10px;
  scrollbar-width: thin;
}

.tree-skeleton {
  padding: 8px 6px;
}

.tree-group {
  margin-bottom: 4px;
}

.tree-group > summary {
  list-style: none;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 7px 9px;
  border-radius: 6px;
  font-size: 11px;
  color: var(--ink-mute);
  font-weight: 600;
  cursor: pointer;
  user-select: none;
}

.tree-group > summary::-webkit-details-marker {
  display: none;
}

.tree-group > summary:hover {
  background: var(--paper-deep);
}

.tree-group > summary :deep(.icon) {
  width: 12px;
  height: 12px;
  transition: transform 0.2s;
}

.tree-group[open] > summary :deep(.icon) {
  transform: rotate(90deg);
}

.group-label {
  flex: 1;
}

.group-count {
  font-size: 10px;
  font-weight: 400;
  color: var(--ink-faint);
}

.tree-item {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 6px 9px 6px 24px;
  border-radius: 6px;
  font-size: 11.5px;
  color: var(--ink-soft);
  text-decoration: none;
  position: relative;
}

.tree-item:hover {
  background: var(--paper-deep);
  color: var(--ink);
}

.tree-item[aria-current='page'] {
  background: var(--paper-raised);
  color: var(--ink);
  font-weight: 600;
  box-shadow: inset 0 0 0 1px var(--line);
}

.tree-item[aria-current='page']::before {
  content: '';
  position: absolute;
  left: 10px;
  top: 12px;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--oxblood);
}

.tree-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tree-item :deep(.tag) {
  flex: none;
  font-size: 9.5px;
  line-height: 15px;
  padding: 0 4px;
}

.rail-foot {
  padding: 9px 16px;
  border-top: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 10.5px;
  color: var(--ink-faint);
}

.rail-foot .mono {
  font-size: 10px;
}

@media (max-width: 1080px) {
  .rail {
    border-right: 1px solid var(--line);
  }
}
</style>
