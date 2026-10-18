<script setup lang="ts">
import { computed } from 'vue'
import type { MetaItem } from './types'

/**
 * 结果元数据网格。值全部来自接口返回，不在这里补算或改写。
 */
const props = defineProps<{
  items: MetaItem[]
  /** 返回体里带回来的列表型字段，按需展示前若干项 */
  lists?: { label: string; values: string[]; emptyText?: string }[]
}>()

const visibleLists = computed(() => (props.lists ?? []).filter((list) => list.values.length > 0))
</script>

<template>
  <div class="ops-meta">
    <div
      v-for="item in items"
      :key="item.label"
      class="ops-meta__item"
      :class="{ wide: item.wide }"
    >
      <span class="ops-meta__k">{{ item.label }}</span>
      <span
        class="ops-meta__v"
        :class="[{ mono: item.mono !== false }, item.tone ? `is-${item.tone}` : '']"
      >
        {{ item.value }}
      </span>
    </div>
  </div>

  <div v-if="visibleLists.length > 0" class="ops-lists">
    <div v-for="list in visibleLists" :key="list.label" class="ops-list">
      <div class="ops-list__head">
        <span>{{ list.label }}</span>
        <span class="mono">{{ list.values.length }}</span>
      </div>
      <ul class="ops-list__items">
        <li v-for="value in list.values" :key="value" class="mono">{{ value }}</li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.ops-meta {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(148px, 1fr));
  gap: 0;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-s);
  background: var(--paper-raised);
  overflow: hidden;
}

.ops-meta__item {
  background: var(--paper-raised);
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  border-right: 1px solid var(--line-soft);
  border-bottom: 1px solid var(--line-soft);
}

.ops-meta__item.wide {
  grid-column: 1 / -1;
}

.ops-meta__k {
  font-size: 10.5px;
  color: var(--ink-faint);
}

.ops-meta__v {
  font-size: 13px;
  color: var(--ink);
  font-weight: 600;
  overflow-wrap: anywhere;
}

.ops-meta__v.mono {
  font-family: var(--mono);
  font-size: 12px;
}

.ops-meta__v.is-ok {
  color: var(--green);
}

.ops-meta__v.is-warn {
  color: var(--amber);
}

.ops-meta__v.is-bad {
  color: var(--oxblood);
}

.ops-lists {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 10px;
}

.ops-list {
  flex: 1 1 220px;
  min-width: 0;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-s);
  background: var(--paper);
  padding: 8px 10px;
}

.ops-list__head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 10.5px;
  color: var(--ink-faint);
}

.ops-list__items {
  margin: 6px 0 0;
  padding: 0;
  list-style: none;
  max-height: 132px;
  overflow: auto;
  font-size: 11px;
  color: var(--ink-soft);
}

.ops-list__items li {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
