<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import { REFUSE_THRESHOLD } from './citations'

/**
 * 拒答卡片。
 *
 * 后端判定是「两路最高原始分都低于各自阈值」才拒答，文案必须写清这个条件，
 * 否则用户会以为「低于阈值就拒答」。
 *
 * 链路事实：慢通道拒答时生成模型其实被调用过（工具返回 hit=false，模型据此转述「查不到」），
 * 所以这里不写「未调用生成模型」，只写「检索侧没有找到可引用依据」。
 */

const props = defineProps<{
  topBm25Score: number | null
  topVectorScore: number | null
  /** 复算失败或没有分数时置 true，卡片改成不带数字的说法 */
  scoresUnavailable: boolean
  /** 本轮复算所用的检索问题；Agent 改写问题时会与用户原话不同 */
  question: string | null
  /** 本轮一共复算了几次 */
  searchCount: number
}>()

const emit = defineEmits<{ handoff: [] }>()

const bm25Pass = computed(() => (props.topBm25Score ?? 0) >= REFUSE_THRESHOLD.bm25)
const vectorPass = computed(() => (props.topVectorScore ?? 0) >= REFUSE_THRESHOLD.vector)

function fmt(value: number | null, digits: number): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return value.toFixed(digits)
}
</script>

<template>
  <div class="refusal" data-testid="refusal-state" role="note">
    <div class="refusal-top">
      <AppIcon name="warning" :size="18" class="refusal-icon" />
      <div>
        <div class="refusal-title">知识库里没有能支撑这个问题的依据</div>
        <div class="refusal-text">
          检索侧没有找到可引用的政策片段，本轮没有产出引用。Agent 依据「查不到」如实转述，
          没有编造政策结论。
        </div>
      </div>
    </div>

    <div v-if="!scoresUnavailable" class="score-table">
      <div class="score-row score-row--head">
        <span>两路最高原始分</span>
        <span>实测</span>
        <span>阈值</span>
        <span>是否达标</span>
      </div>
      <div class="score-row">
        <span class="k">BM25</span>
        <span class="mono">{{ fmt(topBm25Score, 3) }}</span>
        <span class="mono">&lt; {{ REFUSE_THRESHOLD.bm25 }}</span>
        <span class="tag" :class="bm25Pass ? 'green' : 'plain'">{{ bm25Pass ? '达标' : '未达标' }}</span>
      </div>
      <div class="score-row">
        <span class="k">向量相似度</span>
        <span class="mono">{{ fmt(topVectorScore, 3) }}</span>
        <span class="mono">&lt; {{ REFUSE_THRESHOLD.vector }}</span>
        <span class="tag" :class="vectorPass ? 'green' : 'plain'">{{ vectorPass ? '达标' : '未达标' }}</span>
      </div>
      <p class="score-note">
        拒答条件是两路最高原始分<strong>都</strong>低于各自阈值。任一路达标就不会拒答。
      </p>
      <p v-if="question" class="score-note score-note--query">
        复算所用问题：<span class="mono">{{ question }}</span>
        <template v-if="searchCount > 1">（本轮共复算 {{ searchCount }} 次，取最后一次）</template>
      </p>
    </div>
    <p v-else class="score-note">
      本次未取到两路原始分（检索接口复算失败），无法给出分数对比。拒答本身来自检索侧返回。
    </p>

    <div class="refusal-actions">
      <button class="btn ox sm" type="button" @click="emit('handoff')">转人工处理</button>
    </div>
  </div>
</template>

<style scoped>
.refusal {
  border: 1px solid var(--amber-line);
  background: var(--amber-soft);
  border-radius: 10px;
  padding: 13px 15px;
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.refusal-top {
  display: flex;
  align-items: flex-start;
  gap: 9px;
}

.refusal-icon {
  color: var(--amber);
  margin-top: 2px;
  flex: none;
}

.refusal-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink);
}

.refusal-text {
  font-size: 12px;
  color: var(--ink-soft);
  margin-top: 2px;
  line-height: 1.75;
}

.score-table {
  display: flex;
  flex-direction: column;
  gap: 4px;
  background: var(--paper-raised);
  border: 1px solid var(--amber-line);
  border-radius: var(--r-s);
  padding: 8px 10px;
}

.score-row {
  display: grid;
  grid-template-columns: 1.1fr 0.9fr 0.9fr auto;
  gap: 8px;
  align-items: center;
  font-size: 11px;
  color: var(--ink-soft);
}

.score-row--head {
  font-size: 10px;
  color: var(--ink-faint);
  padding-bottom: 4px;
  border-bottom: 1px dashed var(--line);
}

.score-row .k {
  color: var(--ink-mute);
}

.score-note {
  margin: 4px 0 0;
  font-size: 10.5px;
  line-height: 1.7;
  color: var(--ink-mute);
}

.score-note--query {
  padding-top: 4px;
  border-top: 1px dashed var(--line);
  word-break: break-all;
}

.score-note--query .mono {
  font-size: 10px;
  color: var(--ink-soft);
}

.refusal-actions {
  display: flex;
  gap: 7px;
  padding-left: 27px;
  flex-wrap: wrap;
}
</style>
