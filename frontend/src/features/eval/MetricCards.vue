<script setup lang="ts">
import { computed } from 'vue'
import type { GateReport, EvalMetrics } from '@/api/eval'
import {
  formatDelta,
  formatInt,
  formatLatency,
  formatRate,
  formatCompact,
  deltaTone,
} from './format'
import {
  answerPointCounters,
  countersText,
  hallucinationCounters,
  mrrCounters,
  passCounters,
  recallCounters,
  refusalCounters,
  top1Counters,
  type NumeratorDenominator,
} from './counters'

/**
 * 指标卡。
 *
 * 比率统一 4 位小数（与后端 Metrics.round 一致），每张卡都带分子分母；
 * 延迟与 token 单列一组，因为它们的量纲与比率完全不同，不能混进同一组对比。
 * 差值只在门禁给出该指标的基线时显示，容差内一律显示「容差内」，不标好也不标坏。
 */
const props = defineProps<{
  runId: string
  metrics: EvalMetrics
  gate: GateReport | null
  /** 采样量小于 100 时 P99 就是最大值，需在延迟区标注 */
  caseCount: number
}>()

interface RatioCard {
  key: string
  label: string
  value: number
  counters: NumeratorDenominator
  note: string
  /** 越小越好的指标（幻觉率）单独标记 */
  lowerBetter?: boolean
}

const ratioCards = computed<RatioCard[]>(() => {
  const metrics = props.metrics
  return [
    {
      key: 'passRate',
      label: '用例通过率',
      value: metrics.passRate,
      counters: passCounters(metrics),
      note: '通过用例数 / 本批次用例数',
    },
    {
      key: 'recallRate',
      label: '召回率',
      value: metrics.recallRate,
      counters: recallCounters(metrics),
      note: '期望来源进入候选窗口的比例，只算带期望来源的用例',
    },
    {
      key: 'mrr',
      label: 'MRR',
      value: metrics.mrr,
      counters: mrrCounters(metrics),
      note: '期望来源名次的倒数和 / 带期望来源的用例数',
    },
    {
      key: 'top1Rate',
      label: 'Top1 命中率',
      value: metrics.top1Rate,
      counters: top1Counters(metrics),
      note: '期望来源排第 1 的用例比例',
    },
    {
      key: 'refusalCorrectRate',
      label: '拒答正确率',
      value: metrics.refusalCorrectRate,
      counters: refusalCounters(metrics),
      note: '拒答用例判通过数 / 拒答用例数',
    },
    {
      key: 'answerPointRate',
      label: '要点命中率',
      value: metrics.answerPointRate,
      counters: answerPointCounters(metrics),
      note: '命中要点数 / 要点总数',
    },
    {
      key: 'hallucinationRate',
      label: '幻觉率',
      value: metrics.hallucinationRate,
      counters: hallucinationCounters(metrics),
      note: '引用越界 + 禁点命中 + 超范围作答，任一成立即算',
      lowerBetter: true,
    },
  ]
})

/** 门禁里同一指标的基线值与差值。没有基线就不编造对比 */
function comparison(key: string) {
  return props.gate?.comparisons.find((item) => item.metric === key) ?? null
}

function deltaOf(card: RatioCard) {
  const cmp = comparison(card.key)
  if (!cmp || !props.gate) return null
  const tolerance = props.gate.tolerance
  const tone = deltaTone(cmp.delta, tolerance, !card.lowerBetter)
  const inTolerance = Math.abs(cmp.delta) <= tolerance
  return {
    text: formatDelta(cmp.delta),
    baselineText: formatRate(cmp.baseline),
    tone: inTolerance ? 'flat' : tone,
    label: inTolerance ? '容差内' : tone === 'up' ? '变好' : '变差',
    tolerance,
  }
}

const latencyRows = computed(() => {
  const m = props.metrics
  return [
    {
      key: 'fastPathLatencyP50',
      label: '快通道 P50',
      value: m.fastPathLatencyP50,
      sample: m.fastPathCount,
      hint: '快通道命中用例的端到端耗时',
    },
    {
      key: 'fastPathLatencyP99',
      label: '快通道 P99',
      value: m.fastPathLatencyP99,
      sample: m.fastPathCount,
      hint: '快通道命中用例的端到端耗时',
    },
    {
      key: 'ragLatencyP50',
      label: '慢通道 P50',
      value: m.ragLatencyP50,
      sample: m.ragCount,
      hint: '慢通道用例的端到端耗时',
    },
    {
      key: 'ragLatencyP95',
      label: '慢通道 P95',
      value: m.ragLatencyP95,
      sample: m.ragCount,
      hint: '最近秩法，排序后取第 ceil(0.95n) 个',
    },
    {
      key: 'ragLatencyP99',
      label: '慢通道 P99',
      value: m.ragLatencyP99,
      sample: m.ragCount,
      hint: '最近秩法，排序后取第 ceil(0.99n) 个',
    },
  ]
})

const tokenRows = computed(() => {
  const m = props.metrics
  return [
    { key: 'in', label: '慢通道输入 token', total: m.ragTokenInTotal, mean: m.ragTokenInMean },
    { key: 'out', label: '慢通道输出 token', total: m.ragTokenOutTotal, mean: m.ragTokenOutMean },
  ]
})

/** P99 在小样本下就是最大值，标注出来避免当成稳定结论 */
const smallSample = computed(() => props.caseCount < 100 || props.metrics.ragCount < 100)
</script>

<template>
  <section class="panel" aria-labelledby="metric-panel-title">
    <div class="panel-head">
      <h2 id="metric-panel-title" class="panel-title">指标</h2>
      <span class="panel-meta">
        比例统一 4 位小数 · 分母为本批次实跑用例 ·
        <span class="mono">{{ runId }}</span>
      </span>
    </div>

    <div class="panel-body">
      <div class="kpi-grid">
        <article v-for="card in ratioCards" :key="card.key" class="kpi">
          <div class="kpi-label" :title="card.note">
            {{ card.label }}
            <span class="kpi-scope">{{ countersText(card.counters) }}</span>
          </div>
          <div class="kpi-val" :title="`${card.label}（${runId}）`">
            {{ formatRate(card.value) }}
          </div>
          <div class="kpi-delta" :class="deltaOf(card)?.tone ?? 'none'">
            <template v-if="deltaOf(card)">
              <span>{{ deltaOf(card)?.text }}</span>
              <span class="kpi-tag">{{ deltaOf(card)?.label }}</span>
              <span class="kpi-base">基线 {{ deltaOf(card)?.baselineText }}</span>
            </template>
            <span v-else class="kpi-no-base">无基线对比</span>
          </div>
          <p class="kpi-note">{{ card.note }}</p>
        </article>
      </div>

      <div class="sub-grid">
        <div class="sub-block">
          <div class="sub-head">
            <span>延迟分位</span>
            <span class="sub-meta">
              快通道 {{ metrics.fastPathCount }} 条 / 慢通道 {{ metrics.ragCount }} 条
            </span>
          </div>
          <dl class="sub-list">
            <div v-for="row in latencyRows" :key="row.key" class="sub-row">
              <dt :title="row.hint">{{ row.label }}</dt>
              <dd class="mono">{{ formatLatency(row.value) }}</dd>
            </div>
          </dl>
          <p v-if="smallSample" class="sub-foot">
            慢通道样本 {{ metrics.ragCount }} 条，小于 100 时 P99 即最大值，不作稳定结论。
          </p>
        </div>

        <div class="sub-block">
          <div class="sub-head">
            <span>token 消耗</span>
            <span class="sub-meta">慢通道 {{ metrics.ragCount }} 条 · 均值向下取整</span>
          </div>
          <dl class="sub-list">
            <div v-for="row in tokenRows" :key="row.key" class="sub-row">
              <dt>{{ row.label }}</dt>
              <dd class="mono">
                {{ formatInt(row.total) }}
                <span class="sub-mean">均值 {{ formatCompact(row.mean) }}</span>
              </dd>
            </div>
            <div class="sub-row">
              <dt>快通道 token</dt>
              <dd class="mono">
                {{ formatInt(metrics.fastPathTokenIn) }} / {{ formatInt(metrics.fastPathTokenOut) }}
                <span class="sub-mean">入 / 出，恒为 0</span>
              </dd>
            </div>
          </dl>
          <p class="sub-foot">
            快通道不调模型，token 恒为 0 是设计结果，也是「快通道真的没碰模型」的证据。
          </p>
        </div>
      </div>

      <p class="panel-foot">
        以上全部数字来自批次 <span class="mono">{{ runId }}</span>
        <template v-if="gate">
          ；基线对比来自 <span class="mono">{{ gate.baselineRunId }}</span> ，容差 ±{{
            gate.tolerance
          }}。
        </template>
        <template v-else>；本批次无门禁基线，因此不显示差值。</template>
      </p>
    </div>
  </section>
</template>

<style scoped>
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 9px;
  margin-bottom: 14px;
}

.kpi {
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 9px;
  padding: 10px 11px 9px;
  min-width: 0;
}

.kpi-label {
  display: flex;
  align-items: baseline;
  gap: 5px;
  flex-wrap: wrap;
  font-size: 10px;
  color: var(--ink-faint);
}

.kpi-scope {
  font-family: var(--mono);
  font-size: 9px;
  color: var(--ink-mute);
  background: var(--paper-raised);
  border: 1px solid var(--line-soft);
  border-radius: 3px;
  padding: 0 4px;
  line-height: 14px;
}

.kpi-val {
  font-family: var(--mono);
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.02em;
  line-height: 1.2;
  margin-top: 3px;
  color: var(--ink);
}

.kpi-delta {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  font-size: 9.5px;
  font-family: var(--mono);
  margin-top: 3px;
  color: var(--ink-faint);
  min-height: 15px;
}

.kpi-delta.up {
  color: var(--green);
}

.kpi-delta.down {
  color: var(--oxblood);
}

.kpi-delta.flat {
  color: var(--ink-mute);
}

.kpi-tag {
  font-family: var(--sans);
  border: 1px solid currentColor;
  border-radius: 3px;
  padding: 0 4px;
  line-height: 13px;
  font-size: 9px;
}

.kpi-base,
.kpi-no-base {
  font-family: var(--sans);
  color: var(--ink-faint);
}

.kpi-note {
  margin: 5px 0 0;
  font-size: 9.5px;
  line-height: 1.5;
  color: var(--ink-faint);
}

.sub-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(0, 0.9fr);
  gap: 12px;
}

.sub-block {
  border: 1px solid var(--line-soft);
  border-radius: var(--r-s);
  background: var(--paper);
  padding: 9px 11px 10px;
  min-width: 0;
}

.sub-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 10.5px;
  font-weight: 600;
  color: var(--ink-soft);
}

.sub-meta {
  font-size: 9.5px;
  font-weight: 400;
  color: var(--ink-faint);
}

.sub-list {
  margin: 7px 0 0;
}

.sub-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 3px 0;
  border-bottom: 1px dashed var(--line-soft);
}

.sub-row:last-child {
  border-bottom: 0;
}

.sub-row dt {
  font-size: 10.5px;
  color: var(--ink-mute);
}

.sub-row dd {
  margin: 0;
  font-size: 11px;
  color: var(--ink);
  text-align: right;
}

.sub-mean {
  font-family: var(--sans);
  font-size: 9px;
  color: var(--ink-faint);
  margin-left: 4px;
}

.sub-foot {
  margin: 7px 0 0;
  font-size: 9.5px;
  line-height: 1.6;
  color: var(--ink-faint);
}

.panel-foot {
  margin: 11px 0 0;
  padding-top: 9px;
  border-top: 1px solid var(--line-soft);
  font-size: 10px;
  line-height: 1.7;
  color: var(--ink-faint);
}

@media (max-width: 1280px) {
  .kpi-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

@media (max-width: 1080px) {
  .kpi-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .sub-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .kpi-val {
    font-size: 18px;
  }
}
</style>
