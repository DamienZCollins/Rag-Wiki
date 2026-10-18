<script setup lang="ts">
import { computed, ref } from 'vue'
import type { EvalRun } from '@/api/eval'
import { formatDateTime, formatPercent } from './format'
import StatusBadge from '@/components/StatusBadge.vue'
import AppIcon from '@/components/AppIcon.vue'

/**
 * 批次选择。默认最近一批；列表按 startedAt 倒序原样呈现（后端最多给 50 条）。
 * 每一行都带 runId，选中哪一批在标题区和各面板的图例上还能再看到一次。
 */
const props = defineProps<{
  runs: EvalRun[]
  selectedId: string
  loading?: boolean
}>()

const emit = defineEmits<{ select: [runId: string] }>()

const query = ref('')
const expanded = ref(false)

const filtered = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  if (!keyword) return props.runs
  return props.runs.filter(
    (run) => run.runId.toLowerCase().includes(keyword) || run.label.toLowerCase().includes(keyword),
  )
})

/** 折叠时只显示前 5 条，展开后显示全部（50 条也不卡：纯文本行） */
const VISIBLE_WHEN_COLLAPSED = 5
const visibleRuns = computed(() =>
  expanded.value ? filtered.value : filtered.value.slice(0, VISIBLE_WHEN_COLLAPSED),
)

const hiddenCount = computed(() => Math.max(0, filtered.value.length - visibleRuns.value.length))

function armOf(run: EvalRun): string {
  return run.config.fastPathEnabled ? '双通道' : '纯 RAG'
}

function indexOf(run: EvalRun): string {
  const target = run.config.aliasTarget ?? ''
  if (target.includes('_doc')) return '原始文档'
  if (target.includes('_wiki')) return 'Wiki 页面'
  return run.config.indexSource || '未知'
}
</script>

<template>
  <section class="panel">
    <div class="panel-head">
      <h2 class="panel-title">评测批次</h2>
      <span class="panel-meta">
        共 {{ runs.length }} 批 · 按开始时间倒序 · 当前
        <span class="mono">{{ selectedId || '未选择' }}</span>
      </span>
    </div>

    <div class="selector-bar">
      <label class="search">
        <AppIcon name="search" :size="13" />
        <input
          v-model="query"
          type="search"
          placeholder="按 runId 或标签筛选"
          aria-label="按 runId 或标签筛选批次"
        />
      </label>
      <button
        v-if="filtered.length > VISIBLE_WHEN_COLLAPSED || expanded"
        class="btn sm"
        type="button"
        :aria-expanded="expanded"
        @click="expanded = !expanded"
      >
        {{ expanded ? '收起' : `展开全部 ${filtered.length} 批` }}
        <AppIcon :name="expanded ? 'chevron-down' : 'chev'" :size="12" />
      </button>
    </div>

    <ul v-if="visibleRuns.length > 0" class="run-list">
      <li v-for="run in visibleRuns" :key="run.runId">
        <button
          type="button"
          class="run-row"
          :aria-current="run.runId === selectedId ? 'true' : undefined"
          @click="emit('select', run.runId)"
        >
          <span class="run-radio" aria-hidden="true" />
          <span class="run-main">
            <span class="run-label">
              {{ run.label || '未命名批次' }}
              <StatusBadge v-if="run.status !== 'COMPLETED'" :status="run.status" />
            </span>
            <span class="run-id mono">{{ run.runId }}</span>
          </span>
          <span class="run-facts">
            <span class="mono">{{ formatDateTime(run.startedAt) }}</span>
            <span class="dot-sep" />
            <span>{{ run.caseCount }} 条</span>
            <span class="dot-sep" />
            <span>通过率 {{ formatPercent(run.metrics.passRate) }}</span>
            <span class="dot-sep" />
            <span>{{ indexOf(run) }}</span>
            <span class="dot-sep" />
            <span>{{ armOf(run) }}</span>
          </span>
        </button>
      </li>
    </ul>
    <p v-else class="run-empty">
      {{ query ? `没有匹配「${query}」的批次` : '后端还没有已完成的评测批次' }}
    </p>

    <p v-if="hiddenCount > 0" class="run-more">
      还有 {{ hiddenCount }} 批未展开，可用上方搜索框直接定位
    </p>
  </section>
</template>

<style scoped>
.selector-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px 0;
  flex-wrap: wrap;
}

.search {
  flex: 1;
  min-width: 180px;
  display: flex;
  align-items: center;
  gap: 6px;
  height: 27px;
  padding: 0 9px;
  border: 1px solid var(--line);
  border-radius: var(--r-s);
  background: var(--paper);
  color: var(--ink-faint);
}

.search input {
  flex: 1;
  min-width: 0;
  border: 0;
  background: none;
  font-size: 11.5px;
  color: var(--ink);
  outline: none;
}

.run-list {
  list-style: none;
  margin: 10px 0 0;
  padding: 0;
}

.run-row {
  width: 100%;
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 8px 14px;
  border-top: 1px solid var(--line-soft);
  text-align: left;
  transition: background 0.15s;
}

.run-row:hover {
  background: var(--paper);
}

.run-row[aria-current='true'] {
  background: var(--paper-sunken);
}

.run-radio {
  width: 11px;
  height: 11px;
  margin-top: 4px;
  flex: none;
  border-radius: 50%;
  border: 1.5px solid var(--line-strong);
}

.run-row[aria-current='true'] .run-radio {
  border-color: var(--oxblood);
  box-shadow:
    inset 0 0 0 2.5px var(--oxblood),
    inset 0 0 0 11px var(--paper-raised);
}

.run-main {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  flex: 1;
}

.run-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ink);
  line-height: 1.5;
}

.run-row[aria-current='true'] .run-label {
  font-weight: 600;
}

.run-id {
  font-size: 10px;
  color: var(--ink-faint);
}

.run-facts {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
  justify-content: flex-end;
  font-size: 10.5px;
  color: var(--ink-mute);
  max-width: 60%;
}

.run-empty,
.run-more {
  margin: 0;
  padding: 10px 14px 12px;
  font-size: 11px;
  color: var(--ink-faint);
}

.run-more {
  padding-top: 8px;
}

@media (max-width: 1080px) {
  .run-facts {
    max-width: 100%;
    justify-content: flex-start;
  }

  .run-row {
    flex-wrap: wrap;
  }
}

@media (max-width: 760px) {
  .run-facts {
    font-size: 10px;
    gap: 5px;
  }
}
</style>
