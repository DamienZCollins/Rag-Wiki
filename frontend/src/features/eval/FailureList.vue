<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { FailureRow } from './useEvalBundle'
import { formatLatency } from './format'
import { LAYER_HINT, LAYER_TEXT, type LayerKey } from './layers'
import { filterOptions, type LayerFilter } from './failureFilter'
import LayerBadge from './LayerBadge.vue'
import AttributionTrace from './AttributionTrace.vue'
import AppIcon from '@/components/AppIcon.vue'

/**
 * 失败用例列表。
 *
 * 行本身只有 caseId / 问题 / 归因层 / 耗时四列，展开才渲染归因链路，
 * 因此 100+ 行时 DOM 依旧很轻。长问题文本按行数截断，可展开收起。
 */
const props = withDefaults(
  defineProps<{
    runId: string
    rows: FailureRow[]
    total: number
    layerCounts: Record<string, number>
    layer: LayerFilter
    keyword: string
    /** 摘要模式：只显示前 N 条，并给出跳转明细页的入口 */
    limit?: number | null
    showFilters?: boolean
  }>(),
  { limit: null, showFilters: true },
)

const emit = defineEmits<{
  'update:layer': [value: LayerFilter]
  'update:keyword': [value: string]
  jump: []
}>()

/** 展开的 caseId。用 Set 而不是给每行加响应式字段，切换筛选也不会丢展开态 */
const expanded = ref<Set<string>>(new Set())
const expandedLongText = ref<Set<string>>(new Set())

const shown = computed(() => (props.limit ? props.rows.slice(0, props.limit) : props.rows))
const hiddenCount = computed(() => (props.limit ? Math.max(0, props.rows.length - props.limit) : 0))

const options = computed(() =>
  filterOptions(props.layerCounts).map((option) => ({
    ...option,
    label: option.key === 'ALL' ? '全部' : LAYER_TEXT[option.key as LayerKey],
    hint: option.key === 'ALL' ? '不按归因层筛选' : LAYER_HINT[option.key as LayerKey],
  })),
)

// 换批次时清空展开态，避免同名 caseId 误展开
watch(
  () => props.runId,
  () => {
    expanded.value = new Set()
    expandedLongText.value = new Set()
  },
)

function toggleExpand(caseId: string) {
  const next = new Set(expanded.value)
  if (next.has(caseId)) next.delete(caseId)
  else next.add(caseId)
  expanded.value = next
}

function toggleLongText(caseId: string) {
  const next = new Set(expandedLongText.value)
  if (next.has(caseId)) next.delete(caseId)
  else next.add(caseId)
  expandedLongText.value = next
}

function isLong(question: string) {
  return question.length > 42
}

function onKeyword(event: Event) {
  emit('update:keyword', (event.target as HTMLInputElement).value)
}

/** 面板头部的条数说明：摘要模式说清只显示前 N 条，筛选态说清筛出了多少 */
const countSuffix = computed(() => {
  if (props.limit && props.total > props.limit) {
    return ` · 本页显示前 ${shown.value.length} 条`
  }
  if (props.rows.length !== props.total) {
    return ` · 当前筛选出 ${props.rows.length} 条`
  }
  return ''
})

/** 空层（0 条）时点击提示原因，而不是给一个点了没反应的按钮 */
function onPick(key: LayerFilter, count: number) {
  if (key !== 'ALL' && count === 0) return
  emit('update:layer', key)
}
</script>

<template>
  <section class="panel">
    <div class="panel-head">
      <div class="head-left">
        <h2 class="panel-title">失败用例</h2>
        <span class="panel-meta">
          共 {{ total }} 条{{ countSuffix }} · 批次 <span class="mono">{{ runId }}</span>
        </span>
      </div>
      <button v-if="limit" class="btn sm" type="button" @click="emit('jump')">
        查看全部
        <AppIcon name="arrow-right" :size="12" />
      </button>
    </div>

    <div v-if="showFilters" class="filter-bar">
      <div class="layer-chips" role="group" aria-label="按归因层筛选">
        <button
          v-for="option in options"
          :key="option.key"
          type="button"
          class="chip"
          :class="[
            option.key.toLowerCase(),
            { active: layer === option.key, empty: option.count === 0 && option.key !== 'ALL' },
          ]"
          :aria-pressed="layer === option.key"
          :title="option.hint"
          :disabled="option.count === 0 && option.key !== 'ALL'"
          @click="onPick(option.key, option.count)"
        >
          {{ option.label }}
          <span class="chip-n mono">{{ option.count }}</span>
        </button>
      </div>
      <input
        class="keyword"
        type="search"
        :value="keyword"
        placeholder="按 caseId 或问题搜索"
        aria-label="按 caseId 或问题搜索失败用例"
        @input="onKeyword"
      />
    </div>

    <p v-if="rows.length === 0" class="list-empty">
      <template v-if="total === 0">本批次没有失败用例。</template>
      <template v-else-if="layer !== 'ALL'">
        该层在当前条件下没有用例，试试切回「全部」或清空搜索。
      </template>
      <template v-else>没有匹配当前搜索的失败用例。</template>
    </p>

    <ul v-else class="failure-list">
      <li v-for="row in shown" :key="row.caseId" class="failure-item">
        <button
          type="button"
          class="failure-row"
          :aria-expanded="expanded.has(row.caseId)"
          :aria-controls="`trace-${row.caseId}`"
          @click="toggleExpand(row.caseId)"
        >
          <span class="caret" aria-hidden="true">
            <AppIcon :name="expanded.has(row.caseId) ? 'chevron-down' : 'chev'" :size="12" />
          </span>
          <span class="case mono">{{ row.caseId }}</span>
          <span
            class="title"
            :class="{ clamped: !expandedLongText.has(row.caseId) && isLong(row.question) }"
          >
            {{ row.question || '（接口未返回问题文本）' }}
          </span>
          <LayerBadge :layer="row.layer" compact />
          <span class="lat mono">{{ formatLatency(row.latencyMs) }}</span>
        </button>

        <div v-if="isLong(row.question)" class="long-text-bar">
          <button type="button" class="link-btn" @click="toggleLongText(row.caseId)">
            {{ expandedLongText.has(row.caseId) ? '收起问题文本' : '展开问题文本' }}
          </button>
        </div>

        <div v-if="expanded.has(row.caseId)" :id="`trace-${row.caseId}`">
          <AttributionTrace
            :layer="row.layer"
            :detail="row.detail"
            :error="row.error"
            :channel="row.channel"
            :latency-ms="row.latencyMs"
            :token-in="row.tokenIn"
            :token-out="row.tokenOut"
          />
        </div>
      </li>
    </ul>

    <p v-if="hiddenCount > 0" class="list-more">
      已显示前 {{ limit }} 条，还有 {{ hiddenCount }} 条在
      <button type="button" class="link-btn" @click="emit('jump')">失败用例明细页</button>
      查看（可按层筛选与搜索）。
    </p>
  </section>
</template>

<style scoped>
.head-left {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 14px;
  border-bottom: 1px solid var(--line-soft);
  flex-wrap: wrap;
}

.layer-chips {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 7px;
  font-size: 10.5px;
  line-height: 18px;
  border: 1px solid var(--line);
  border-radius: 4px;
  background: var(--paper-raised);
  color: var(--ink-mute);
  transition:
    background 0.15s,
    border-color 0.15s,
    color 0.15s;
}

.chip:hover:not(:disabled) {
  border-color: var(--line-strong);
  color: var(--ink);
}

.chip.active {
  background: var(--paper-sunken);
  border-color: var(--line-strong);
  color: var(--ink);
  font-weight: 600;
}

.chip.empty:disabled {
  opacity: 0.42;
  cursor: not-allowed;
}

.chip.compile.active {
  background: var(--green-soft);
  border-color: var(--green-line);
  color: var(--green);
}

.chip.recall.active {
  background: var(--amber-soft);
  border-color: var(--amber-line);
  color: var(--amber);
}

.chip.rerank.active {
  background: var(--blue-soft);
  border-color: var(--blue-line);
  color: var(--blue);
}

.chip.generation.active {
  background: var(--oxblood-soft);
  border-color: var(--oxblood-line);
  color: var(--oxblood);
}

.chip.fallback.active {
  background: var(--paper);
  border-color: var(--line-strong);
  color: var(--ink-soft);
}

.chip.none.active {
  background: var(--paper-deep);
  border-color: var(--line-strong);
  color: var(--ink-mute);
}

.chip-n {
  font-size: 9px;
  color: var(--ink-faint);
}

.chip.active .chip-n {
  color: inherit;
}

.keyword {
  flex: 1;
  min-width: 160px;
  height: 25px;
  padding: 0 9px;
  font-size: 11px;
  border: 1px solid var(--line);
  border-radius: var(--r-s);
  background: var(--paper);
  color: var(--ink);
  outline: none;
}

.failure-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.failure-item + .failure-item {
  border-top: 1px solid var(--line-soft);
}

.failure-row {
  width: 100%;
  display: grid;
  grid-template-columns: 16px 94px minmax(0, 1fr) 56px 62px;
  gap: 8px;
  align-items: center;
  padding: 8px 14px;
  text-align: left;
  font-size: 11.5px;
  transition: background 0.15s;
}

.failure-row:hover {
  background: var(--paper);
}

.failure-row[aria-expanded='true'] {
  background: var(--paper);
}

.caret {
  color: var(--ink-faint);
  display: grid;
  place-items: center;
}

.failure-row .case {
  font-size: 10px;
  color: var(--ink-faint);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.failure-row .title {
  color: var(--ink-soft);
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.failure-row .title.clamped {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.failure-row .lat {
  font-size: 10.5px;
  text-align: right;
  color: var(--ink-mute);
}

.long-text-bar {
  padding: 0 14px 6px 128px;
}

.link-btn {
  font-size: 10px;
  color: var(--teal);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.list-empty,
.list-more {
  margin: 0;
  padding: 12px 14px;
  font-size: 11px;
  line-height: 1.7;
  color: var(--ink-faint);
}

.list-more {
  border-top: 1px solid var(--line-soft);
}

@media (max-width: 760px) {
  .failure-row {
    grid-template-columns: 14px minmax(0, 1fr) 52px 54px;
    padding: 8px 10px;
    row-gap: 2px;
  }

  .failure-row .case {
    grid-column: 1 / -1;
    padding-left: 22px;
    font-size: 9.5px;
  }

  .failure-row .title {
    font-size: 11px;
  }

  .long-text-bar {
    padding-left: 10px;
  }

  .filter-bar {
    padding: 8px 10px;
  }

  .keyword {
    min-width: 100%;
  }
}
</style>
