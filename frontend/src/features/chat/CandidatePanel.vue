<script setup lang="ts">
import { computed, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import SkeletonBlock from '@/components/SkeletonBlock.vue'
import type { FastEntry } from '@/api/fastpath'
import type { ErrorPhase } from '@/api/types'

/**
 * 快通道候选区。
 *
 * 后端实测返回 68 条候选，一次铺开会挤爆对话流，所以默认只显示前 6 条，其余折叠。
 * 悬浮或聚焦时展示 confidence 与 origin：DERIVED 有来源条目可追溯，DISTILLED 是慢通道沉淀，语义不同。
 */

const props = defineProps<{
  entries: FastEntry[]
  visibleEntries: FastEntry[]
  /** 是否铺开全部候选 */
  expanded: boolean
  phase: ErrorPhase
  error: string | null
  /** 回合进行中禁用候选点击 */
  busy: boolean
  /** 会话已有消息后收起为一行提示 */
  collapsed: boolean
}>()

const emit = defineEmits<{
  pick: [entry: FastEntry]
  'toggle-all': []
  'open-zone': []
  'close-zone': []
  reload: []
}>()

const activeId = ref<string | null>(null)

const total = computed(() => props.entries.length)
const hiddenCount = computed(() => Math.max(0, total.value - props.visibleEntries.length))

const ORIGIN_LABEL: Record<string, string> = {
  DERIVED: '由 Wiki 条目派生',
  DISTILLED: '慢通道沉淀',
}

function originLabel(origin: string): string {
  return ORIGIN_LABEL[origin] ?? origin
}

function sourceOf(entry: FastEntry): string {
  if (entry.sourcePageId) return entry.sourcePageId
  return '无来源条目（沉淀候选）'
}

function pick(entry: FastEntry) {
  if (props.busy) return
  activeId.value = entry.entryId
  emit('pick', entry)
}
</script>

<template>
  <section class="candidate-zone" data-testid="candidate-panel" aria-label="快通道候选">
    <!-- 已有消息：收成一行，不遮挡对话流 -->
    <div v-if="collapsed" class="candidate-collapsed">
      <AppIcon name="bolt" :size="13" />
      <span>快通道候选 {{ total }} 条已收起</span>
      <button class="btn ghost sm" type="button" @click="emit('open-zone')">重新展开</button>
    </div>

    <template v-else>
      <div class="zone-title">
        <b>这位客户可能想问</b>
        <span class="line"></span>
        <span class="tag teal" title="直接命中已发布条目，不调用模型">快通道 · 0 次模型调用</span>
        <span class="tag plain mono">{{ total }} 条</span>
      </div>

      <div v-if="phase === 'loading'" class="candidate-grid" aria-busy="true">
        <div v-for="n in 6" :key="n" class="cand-skeleton">
          <SkeletonBlock :lines="2" height="12px" />
        </div>
      </div>

      <div v-else-if="phase === 'error'" class="candidate-error" role="alert">
        <AppIcon name="alert" :size="15" />
        <span>候选列表加载失败：{{ error }}。不影响下方直接提问。</span>
        <button class="btn sm" type="button" @click="emit('reload')">重试</button>
      </div>

      <div v-else-if="total === 0" class="candidate-empty">
        这个场景暂时没有可用的快通道候选，直接提问会走 RAG 检索。
      </div>

      <template v-else>
        <div class="candidate-grid">
          <button
            v-for="entry in visibleEntries"
            :key="entry.entryId"
            class="cand"
            :class="{ 'is-selected': activeId === entry.entryId }"
            data-testid="candidate-item"
            type="button"
            :disabled="busy"
            :aria-label="`${entry.question}，置信度 ${entry.confidence.toFixed(2)}，${originLabel(entry.origin)}`"
            @click="pick(entry)"
          >
            <span class="cand-title">{{ entry.question }}</span>
            <span class="cand-meta">
              <span class="tag" :class="entry.origin === 'DERIVED' ? 'teal' : 'amber'">
                {{ originLabel(entry.origin) }}
              </span>
              <span class="mono">conf {{ entry.confidence.toFixed(2) }}</span>
            </span>
            <span class="cand-pop" role="tooltip">
              <span class="pop-row"><span class="pop-k">置信度</span><span class="mono">{{ entry.confidence.toFixed(2) }}</span></span>
              <span class="pop-row"><span class="pop-k">来源类型</span><span>{{ entry.origin }} · {{ originLabel(entry.origin) }}</span></span>
              <span class="pop-row"><span class="pop-k">来源条目</span><span class="mono">{{ sourceOf(entry) }}</span></span>
              <span class="pop-row"><span class="pop-k">命中次数</span><span class="mono">{{ entry.hitCount ?? '—' }}</span></span>
            </span>
          </button>
        </div>

        <div class="zone-hint">
          <AppIcon name="info" :size="12" />
          <span>点击候选直接取已发布条目作答；未命中会自动转 RAG 检索，界面上会标注「已转检索」。</span>
        </div>

        <div class="zone-more">
          <button
            v-if="!expanded && hiddenCount > 0"
            class="btn ghost sm"
            type="button"
            @click="emit('toggle-all')"
          >
            展开全部 {{ total }} 条
            <AppIcon name="chevron-down" :size="13" />
          </button>
          <button v-else-if="expanded" class="btn ghost sm" type="button" @click="emit('toggle-all')">
            收起，只看前 6 条
            <AppIcon name="chev" :size="13" />
          </button>
          <button class="btn ghost sm" type="button" @click="emit('close-zone')">收起候选区</button>
        </div>
      </template>
    </template>
  </section>
</template>

<style scoped>
.candidate-zone {
  margin-bottom: 4px;
}

.candidate-collapsed {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 10.5px;
  color: var(--ink-faint);
  background: var(--paper);
  border: 1px dashed var(--line-strong);
  border-radius: 8px;
  padding: 6px 10px;
}

.candidate-collapsed .icon {
  color: var(--teal);
  flex: none;
}

.candidate-collapsed .btn {
  margin-left: auto;
}

.zone-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  color: var(--ink-mute);
  margin-bottom: 9px;
  flex-wrap: wrap;
}

.zone-title b {
  font-size: 12px;
  color: var(--ink-soft);
  font-weight: 700;
}

.zone-title .line {
  height: 1px;
  background: var(--line);
  flex: 1;
  min-width: 12px;
}

.candidate-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 9px;
}

.cand-skeleton {
  border: 1px solid var(--line);
  border-radius: 9px;
  padding: 11px 12px;
  min-height: 96px;
  background: var(--paper-raised);
  display: flex;
  align-items: center;
}

.cand {
  position: relative;
  text-align: left;
  background: var(--paper-raised);
  border: 1px solid var(--line);
  border-radius: 9px;
  padding: 11px 12px 10px;
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-height: 96px;
  transition:
    border-color 0.15s,
    background 0.15s,
    transform 0.12s;
  overflow: visible;
}

.cand:hover:not(:disabled),
.cand:focus-visible {
  border-color: var(--line-strong);
  background: var(--paper);
  transform: translateY(-1px);
}

.cand:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.cand.is-selected {
  border-color: var(--teal);
  background: var(--teal-soft);
}

.cand-title {
  font-size: 12.5px;
  font-weight: 600;
  line-height: 1.45;
  padding-right: 4px;
  color: var(--ink);
}

.cand-meta {
  font-size: 10px;
  color: var(--ink-mute);
  margin-top: auto;
  display: flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
}

.cand-meta .mono {
  font-size: 9.5px;
  color: var(--ink-faint);
}

/* 悬浮/聚焦信息：键盘聚焦也能看到 */
.cand-pop {
  position: absolute;
  left: 0;
  right: 0;
  top: calc(100% + 6px);
  z-index: 12;
  display: none;
  flex-direction: column;
  gap: 3px;
  background: var(--paper-raised);
  border: 1px solid var(--line-strong);
  border-radius: var(--r-s);
  box-shadow: var(--shadow);
  padding: 8px 10px;
  font-size: 10.5px;
  color: var(--ink-soft);
  pointer-events: none;
}

.cand:hover .cand-pop,
.cand:focus-visible .cand-pop {
  display: flex;
}

.pop-row {
  display: flex;
  gap: 8px;
  align-items: baseline;
  justify-content: space-between;
}

.pop-k {
  color: var(--ink-faint);
  flex: none;
}

.candidate-error {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--oxblood-line);
  background: var(--oxblood-soft);
  color: var(--ink-soft);
  border-radius: 8px;
  padding: 8px 11px;
  font-size: 11.5px;
  flex-wrap: wrap;
}

.candidate-error .icon {
  color: var(--oxblood);
  flex: none;
}

.candidate-error .btn {
  margin-left: auto;
}

.candidate-empty {
  font-size: 11.5px;
  color: var(--ink-mute);
  border: 1px dashed var(--line-strong);
  border-radius: 8px;
  padding: 9px 11px;
}

.zone-hint {
  font-size: 10.5px;
  color: var(--ink-faint);
  margin-top: 8px;
  display: flex;
  align-items: flex-start;
  gap: 5px;
  line-height: 1.65;
}

.zone-hint .icon {
  margin-top: 2px;
  flex: none;
}

.zone-more {
  margin-top: 7px;
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

@media (max-width: 760px) {
  .candidate-grid {
    grid-template-columns: 1fr;
  }
}
</style>
