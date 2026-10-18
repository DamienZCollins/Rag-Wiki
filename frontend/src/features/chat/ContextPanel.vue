<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import { formatScore, REFUSE_THRESHOLD } from './citations'
import type { ChatMessage, CitationRef, TraceStep } from './types'

/**
 * 右侧上下文面板。
 *
 * 原型里的「客户」Tab 没有数据源（后端不提供客户档案），直接不做。
 * 只保留两个真实可用的 Tab：引用（本轮 chunks）与链路（本轮事件序列 + token 消耗）。
 */

const props = defineProps<{
  message: ChatMessage | null
  citations: CitationRef[]
  tab: 'citations' | 'trace'
}>()

const emit = defineEmits<{
  'update:tab': [tab: 'citations' | 'trace']
  'open-citation': [index: number, event: MouseEvent]
}>()

const tabs = [
  { key: 'citations' as const, label: '引用' },
  { key: 'trace' as const, label: '链路' },
]

const search = computed(() => props.message?.search ?? null)
const events = computed<TraceStep[]>(() => props.message?.events ?? [])

const scoresAvailable = computed(() => search.value !== null)

function atLabel(step: TraceStep): string {
  if (step.at < 1000) return `+${step.at} ms`
  return `+${(step.at / 1000).toFixed(2)} s`
}

/** 引用条上的分数条宽度：按 RRF 分相对参考值归一，纯装饰，不作为判断依据 */
function barWidth(item: CitationRef): number {
  if (!item.enriched || item.rrfScore === null) return 0
  const scaled = Math.min(1, item.rrfScore / 0.035)
  return Math.max(8, Math.round(scaled * 100))
}
</script>

<template>
  <aside class="context" aria-label="会话上下文">
    <div class="context-tabs" role="tablist" aria-label="上下文视图">
      <button
        v-for="item in tabs"
        :key="item.key"
        class="ctx-tab"
        type="button"
        role="tab"
        :aria-selected="tab === item.key"
        :aria-controls="`ctx-panel-${item.key}`"
        @click="emit('update:tab', item.key)"
      >
        {{ item.label }}
        <span v-if="item.key === 'citations' && citations.length > 0" class="mono">{{ citations.length }}</span>
        <span v-else-if="item.key === 'trace' && events.length > 0" class="mono">{{ events.length }}</span>
      </button>
    </div>

    <div class="context-scroll">
      <!-- 引用 -->
      <section
        v-show="tab === 'citations'"
        :id="'ctx-panel-citations'"
        class="ctx-panel"
        role="tabpanel"
        aria-label="本轮引用"
      >
        <p class="evidence-note">
          <AppIcon name="doc" :size="12" />
          <span>每条引用都能定位到片段、来源文档与 Wiki 条目。点击可展开两路原始分与名次。</span>
        </p>

        <div v-if="citations.length > 0" class="source-list">
          <button
            v-for="item in citations"
            :key="`${item.chunkId}-${item.index}`"
            class="source-item"
            type="button"
            :aria-label="`打开引用 ${item.index}：${item.title}`"
            @click="emit('open-citation', item.index, $event)"
          >
            <span class="n">{{ item.index }}</span>
            <span class="source-text">
              <span class="source-title">{{ item.title }}</span>
              <span class="source-meta">
                {{ item.headingPath || '未标注章节' }}
                <template v-if="item.version"> · {{ item.version }}</template>
              </span>
            </span>
            <span class="pct mono">{{ item.enriched ? '已复算' : '仅元信息' }}</span>
            <span class="bar">
              <i :style="{ width: `${barWidth(item)}%` }"></i>
            </span>
          </button>
        </div>
        <p v-else-if="message?.toolMiss" class="ctx-empty">
          本轮检索返回 <code class="mono">hit=false</code>，没有可引用片段。
        </p>
        <p v-else-if="message?.refused" class="ctx-empty">本轮检索判定拒答，没有可引用片段。</p>
        <p v-else class="ctx-empty">本轮还没有引用。走 RAG 的回答会在这里列出检索到的片段。</p>

        <div class="section-block">
          <div class="section-head">
            <span>检索侧数据</span>
            <span class="mono section-head-note">
              {{ scoresAvailable ? '同一问题复算' : '未取到' }}
            </span>
          </div>
          <div class="section-body">
            <template v-if="scoresAvailable && search">
              <div class="kv-row">
                <span class="k">是否拒答</span>
                <span class="v">{{ search.refused ? '是（两路都低于阈值）' : '否' }}</span>
              </div>
              <div class="kv-row">
                <span class="k">最高 BM25</span>
                <span class="v mono">
                  {{ formatScore(search.topBm25Score, 3) }} / 阈值 {{ REFUSE_THRESHOLD.bm25 }}
                </span>
              </div>
              <div class="kv-row">
                <span class="k">最高向量</span>
                <span class="v mono">
                  {{ formatScore(search.topVectorScore, 3) }} / 阈值 {{ REFUSE_THRESHOLD.vector }}
                </span>
              </div>
              <div class="kv-row">
                <span class="k">召回片段</span>
                <span class="v mono">{{ search.chunks.length }} 条</span>
              </div>
            </template>
            <p v-else class="ctx-empty ctx-empty--inline">
              本轮没有调用 <code class="mono">search_knowledge</code>，或复算未成功。
            </p>
          </div>
        </div>
      </section>

      <!-- 链路 -->
      <section
        v-show="tab === 'trace'"
        :id="'ctx-panel-trace'"
        class="ctx-panel"
        role="tabpanel"
        aria-label="本轮事件链路"
      >
        <p class="evidence-note">
          <AppIcon name="chart" :size="12" />
          <span>按事件流还原本轮：STARTED → THINKING → TOOL_CALL → TOOL_RESULT → TEXT_DELTA → FINISHED。</span>
        </p>

        <div v-if="events.length > 0" class="steps">
          <div v-for="(step, index) in events" :key="index" class="step">
            <span class="step-dot">{{ index + 1 }}</span>
            <div class="step-main">
              <div class="step-title">
                <span>{{ step.label }}</span>
                <span class="mono">{{ atLabel(step) }}</span>
              </div>
              <div class="step-body">{{ step.detail }}</div>
            </div>
          </div>
        </div>
        <p v-else class="ctx-empty">本轮还没有事件。发一句话后会在这里逐步记录。</p>

        <div class="section-block">
          <div class="section-head">
            <span>本轮消耗</span>
            <span class="mono section-head-note">{{ message?.channel ?? '—' }}</span>
          </div>
          <div class="section-body">
            <div class="kv-row">
              <span class="k">tokenIn</span>
              <span class="v mono">{{ message?.tokenIn ?? 0 }}</span>
            </div>
            <div class="kv-row">
              <span class="k">tokenOut</span>
              <span class="v mono">{{ message?.tokenOut ?? 0 }}</span>
            </div>
            <div class="kv-row">
              <span class="k">终态</span>
              <span class="v mono">{{ message?.finishReason ?? '进行中' }}</span>
            </div>
            <div v-if="message && message.tokenIn === 0 && message.tokenOut === 0 && message.finishReason === 'COMPLETED'" class="kv-row">
              <span class="k">说明</span>
              <span class="v">快通道直答，零模型调用</span>
            </div>
          </div>
        </div>
      </section>
    </div>
  </aside>
</template>

<style scoped>
.context {
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--paper);
  border-left: 1px solid var(--line);
}

.context-tabs {
  display: flex;
  padding: 10px 12px 0;
  gap: 2px;
  border-bottom: 1px solid var(--line);
}

.ctx-tab {
  padding: 7px 10px 9px;
  font-size: 11.5px;
  color: var(--ink-mute);
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
}

.ctx-tab[aria-selected='true'] {
  color: var(--ink);
  font-weight: 600;
  border-bottom-color: var(--oxblood);
}

.ctx-tab .mono {
  font-size: 9.5px;
  color: var(--ink-faint);
  margin-left: 3px;
}

.context-scroll {
  flex: 1;
  overflow: auto;
  padding: 14px 14px 20px;
  scrollbar-width: thin;
}

.ctx-panel {
  display: flex;
  flex-direction: column;
  gap: 13px;
}

.evidence-note {
  margin: 0;
  font-size: 10px;
  color: var(--ink-faint);
  display: flex;
  gap: 5px;
  align-items: flex-start;
  line-height: 1.7;
}

.evidence-note .icon {
  margin-top: 2px;
  flex: none;
}

.source-list {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.source-item {
  width: 100%;
  text-align: left;
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 9px 10px;
  display: grid;
  grid-template-columns: 20px minmax(0, 1fr) auto;
  gap: 7px;
  align-items: start;
  transition:
    border-color 0.15s,
    background 0.15s;
}

.source-item:hover {
  border-color: var(--teal-line);
  background: var(--teal-soft);
}

.source-item .n {
  font-family: var(--mono);
  font-size: 10px;
  color: var(--teal);
  background: var(--teal-soft);
  border: 1px solid var(--teal-line);
  border-radius: 4px;
  text-align: center;
  line-height: 17px;
  height: 18px;
}

.source-text {
  min-width: 0;
}

.source-title {
  display: block;
  font-size: 11.5px;
  font-weight: 600;
  line-height: 1.45;
  color: var(--ink);
}

.source-meta {
  display: block;
  font-size: 10px;
  color: var(--ink-faint);
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.source-item .pct {
  font-size: 9.5px;
  color: var(--teal);
  font-weight: 600;
  white-space: nowrap;
}

.source-item .bar {
  grid-column: 2 / -1;
  height: 2px;
  background: var(--line);
  border-radius: 2px;
  overflow: hidden;
  margin-top: 2px;
}

.source-item .bar > i {
  display: block;
  height: 100%;
  background: var(--teal);
  border-radius: 2px;
}

.ctx-empty {
  margin: 0;
  font-size: 11px;
  color: var(--ink-mute);
  line-height: 1.75;
  border: 1px dashed var(--line-strong);
  border-radius: 8px;
  padding: 9px 11px;
}

.ctx-empty--inline {
  border: 0;
  padding: 0;
  color: var(--ink-faint);
  font-size: 10.5px;
}

.ctx-empty code {
  font-size: 10px;
  padding: 0 3px;
  border-radius: 3px;
  background: var(--paper-sunken);
}

.section-block {
  background: var(--paper-raised);
  border: 1px solid var(--line);
  border-radius: 10px;
  overflow: hidden;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 9px 12px;
  border-bottom: 1px solid var(--line-soft);
  font-size: 11px;
  font-weight: 700;
  color: var(--ink-soft);
}

.section-head-note {
  font-size: 9.5px;
  font-weight: 400;
  color: var(--ink-faint);
}

.section-body {
  padding: 10px 12px;
}

.kv-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 5px 0;
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
}

.kv-row .v.mono {
  font-size: 11px;
}

.steps {
  background: var(--paper-raised);
  border: 1px solid var(--line);
  border-radius: 9px;
  padding: 11px;
  display: flex;
  flex-direction: column;
}

.step {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr);
  gap: 8px;
  position: relative;
  padding: 5px 0 9px;
}

.step:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 8px;
  top: 21px;
  bottom: -4px;
  width: 1px;
  background: var(--line);
}

.step-dot {
  width: 17px;
  height: 17px;
  border-radius: 50%;
  background: var(--paper-raised);
  border: 1px solid var(--teal-line);
  display: grid;
  place-items: center;
  color: var(--teal);
  font-size: 8px;
  font-family: var(--mono);
}

.step-main {
  min-width: 0;
}

.step-title {
  font-size: 10.5px;
  color: var(--ink-faint);
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.step-title .mono {
  font-size: 9px;
  color: var(--ink-faint);
  margin-left: auto;
}

.step-body {
  font-size: 11.5px;
  color: var(--ink-soft);
  margin-top: 1px;
  word-break: break-word;
  white-space: pre-wrap;
  max-height: 120px;
  overflow: auto;
  scrollbar-width: thin;
}

@media (max-width: 1080px) {
  .context {
    display: none;
  }
}
</style>
