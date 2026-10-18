<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'

/**
 * 来源追溯。sources 是来源文档的 doc_id 列表。
 * 后端没有「按 doc_id 取原文」的 HTTP 接口，因此这里只做标识展示与复制，
 * 不伪造原文片段入口；空来源时明确写「无来源」，而不是留空列表。
 */

const props = defineProps<{
  sources: string[]
}>()

const emit = defineEmits<{ copy: [sourceId: string] }>()
</script>

<template>
  <section class="side-card" aria-labelledby="source-head">
    <div class="side-card-head">
      <span id="source-head">来源追溯</span>
      <span class="mono" :title="`共 ${props.sources.length} 篇来源文档`">
        {{ sources.length }} 篇
      </span>
    </div>
    <div class="side-card-body">
      <template v-if="sources.length > 0">
        <div v-for="source in sources" :key="source" class="source-row">
          <AppIcon name="doc" :size="13" />
          <span class="source-id mono">{{ source }}</span>
          <button
            type="button"
            class="source-copy"
            :aria-label="`复制来源标识 ${source}`"
            title="复制来源标识"
            @click="emit('copy', source)"
          >
            <AppIcon name="copy" :size="12" />
          </button>
        </div>
        <p class="source-note">来源标识对应语料文档的 doc_id，可用于反查原文。</p>
      </template>
      <p v-else class="empty-line">
        <AppIcon name="info" :size="12" />
        无来源：该条目不是由政策文档编译而来。
      </p>
    </div>
  </section>
</template>

<style scoped>
.side-card {
  background: var(--paper-raised);
  border: 1px solid var(--line);
  border-radius: 10px;
  overflow: hidden;
}

.side-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 9px 11px;
  border-bottom: 1px solid var(--line-soft);
  font-size: 11px;
  font-weight: 700;
  color: var(--ink-soft);
}

.side-card-head .mono {
  font-size: 9.5px;
  font-weight: 400;
  color: var(--ink-faint);
}

.side-card-body {
  padding: 8px 11px 9px;
}

.source-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 0;
}

.source-row :deep(.icon) {
  width: 13px;
  height: 13px;
  color: var(--ink-faint);
  flex: none;
}

.source-id {
  flex: 1;
  min-width: 0;
  font-size: 10.5px;
  color: var(--ink-soft);
  word-break: break-all;
}

.source-copy {
  color: var(--ink-faint);
  display: grid;
  place-items: center;
  padding: 2px;
  border-radius: 4px;
  flex: none;
}

.source-copy:hover {
  background: var(--paper);
  color: var(--ink);
}

.source-note {
  margin: 6px 0 0;
  font-size: 10px;
  line-height: 1.6;
  color: var(--ink-faint);
}

.empty-line {
  display: flex;
  align-items: flex-start;
  gap: 5px;
  margin: 0;
  font-size: 11px;
  line-height: 1.7;
  color: var(--ink-faint);
}

.empty-line :deep(.icon) {
  width: 12px;
  height: 12px;
  margin-top: 3px;
  flex: none;
}
</style>
