<script setup lang="ts">
import { computed } from 'vue'
import StatusBadge from '@/components/StatusBadge.vue'
import type { WikiPageSummary } from '@/api/wiki'
import { formatDateTime } from './format'
import { formatEffectivePeriod } from './period'

/**
 * 条目元信息：摘要字段里除标题外的全部字段都在这里出现，
 * 正文区的 kicker 与右栏「版本与来源」共用同一份数据。
 */

const props = defineProps<{
  summary: WikiPageSummary
  variant?: 'strip' | 'rows'
}>()

const variant = computed(() => props.variant ?? 'strip')

const effectiveText = computed(() =>
  formatEffectivePeriod(props.summary.effectiveAt, props.summary.expiresAt),
)

const sourceText = computed(() => {
  const count = props.summary.sources.length
  return count === 0 ? '无来源' : `来源 ${count} 篇`
})

const rows = computed(() => [
  { key: 'pageId', label: '条目标识', value: props.summary.pageId, mono: true },
  { key: 'version', label: '当前版本', value: props.summary.version, mono: true },
  { key: 'status', label: '状态', value: props.summary.status, mono: false },
  { key: 'effective', label: '生效期', value: effectiveText.value, mono: true },
  {
    key: 'updatedAt',
    label: '更新时间',
    value: formatDateTime(props.summary.updatedAt),
    mono: true,
  },
  { key: 'outbound', label: '出链', value: `${props.summary.outboundCount} 条`, mono: false },
  { key: 'inbound', label: '反向链接', value: `${props.summary.inboundCount} 条`, mono: false },
])
</script>

<template>
  <div v-if="variant === 'strip'" class="meta-strip">
    <span class="tag teal">{{ summary.version }}</span>
    <StatusBadge :status="summary.status" />
    <span class="tag">生效期 {{ effectiveText }}</span>
    <span class="tag blue">{{ sourceText }}</span>
    <span class="tag" :title="`出链 ${summary.outboundCount} 条`">
      出链 {{ summary.outboundCount }}
    </span>
    <span class="tag" :title="`反向链接 ${summary.inboundCount} 条`">
      反向链接 {{ summary.inboundCount }}
    </span>
  </div>

  <div v-else class="kv-list">
    <div v-for="row in rows" :key="row.key" class="kv-row">
      <span class="k">{{ row.label }}</span>
      <span class="v" :class="{ mono: row.mono }">{{ row.value }}</span>
    </div>
    <div class="kv-row">
      <span class="k">来源</span>
      <span class="v">{{ sourceText }}</span>
    </div>
  </div>
</template>

<style scoped>
.meta-strip {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding-bottom: 15px;
  border-bottom: 1px solid var(--line);
}

.kv-list {
  display: flex;
  flex-direction: column;
}

.kv-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 3px 0;
  font-size: 11.5px;
}

.kv-row .k {
  color: var(--ink-mute);
  flex: none;
}

.kv-row .v {
  color: var(--ink-soft);
  text-align: right;
  font-weight: 600;
  word-break: break-all;
}

.kv-row .v.mono {
  font-size: 11px;
}
</style>
