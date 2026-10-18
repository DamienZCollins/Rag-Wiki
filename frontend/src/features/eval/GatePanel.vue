<script setup lang="ts">
import { computed } from 'vue'
import type { GateReport } from '@/api/eval'
import { formatDelta, formatRate } from './format'
import AppIcon from '@/components/AppIcon.vue'
import ErrorState from '@/components/ErrorState.vue'

/**
 * 回归门禁。
 *
 * 只比比率型指标，容差默认 0.02：差值落在容差内不判退化（LLM 采样本身有波动）。
 * 未通过项高亮，并写明是「真实退化」还是「容差内波动」，
 * 避免把噪声当成退化，也避免把退化读成噪声。
 */
const props = defineProps<{
  runId: string
  gate: GateReport | null
  gateError: unknown
  /** 本批次失败清单，用于核对门禁附录的失败用例 */
  failedTotal: number
}>()

const emit = defineEmits<{ retry: [] }>()

/** 越低越好的指标。口径见《评测指标口径》第 9 节 */
const LOWER_IS_BETTER = new Set(['hallucinationRate'])

interface MetricLabel {
  label: string
  note: string
}

const METRIC_META: Record<string, MetricLabel> = {
  passRate: { label: '用例通过率', note: '通过用例数 / 用例数' },
  recallRate: { label: '召回率', note: '期望来源进入候选窗口的比例' },
  mrr: { label: 'MRR', note: '期望来源名次的倒数和 / 带期望来源的用例数' },
  top1Rate: { label: 'Top1 命中率', note: '期望来源排第 1 的比例' },
  refusalCorrectRate: { label: '拒答正确率', note: '拒答用例判通过数 / 拒答用例数' },
  answerPointRate: { label: '要点命中率', note: '命中要点数 / 要点总数' },
  hallucinationRate: { label: '幻觉率', note: '越低越好' },
}

const rows = computed(() => {
  const gate = props.gate
  if (!gate) return []
  return gate.comparisons.map((item) => {
    const lowerBetter = LOWER_IS_BETTER.has(item.metric)
    const inTolerance = Math.abs(item.delta) <= gate.tolerance
    const improved = lowerBetter ? item.delta < 0 : item.delta > 0
    // 退化 = 超出容差且方向变坏；容差内一律算「容差内波动」
    const regressed = item.regressed
    const state = regressed ? 'regressed' : inTolerance ? 'tolerance' : improved ? 'improved' : 'ok'
    return {
      key: item.metric,
      label: METRIC_META[item.metric]?.label ?? item.metric,
      note: METRIC_META[item.metric]?.note ?? '',
      metric: item.metric,
      baseline: formatRate(item.baseline),
      current: formatRate(item.current),
      delta: formatDelta(item.delta),
      deltaRaw: item.delta,
      tolerance: gate.tolerance,
      toleranceText: `${lowerBetter ? '+' : '−'}${gate.tolerance.toFixed(2)}`,
      regressed,
      state,
      verdict: regressed
        ? lowerBetter
          ? '退化：升破基线 + 容差'
          : '退化：跌破基线 − 容差'
        : inTolerance
          ? '容差内波动，不计退化'
          : improved
            ? '优于基线'
            : '变动但未越界',
    } as const
  })
})

const regressedRows = computed(() => rows.value.filter((row) => row.regressed))
const toleranceRows = computed(() =>
  rows.value.filter((row) => !row.regressed && row.state === 'tolerance'),
)
</script>

<template>
  <section class="panel" aria-labelledby="gate-panel-title">
    <div class="panel-head">
      <div class="head-left">
        <h2 id="gate-panel-title" class="panel-title">回归门禁</h2>
        <span class="panel-meta">
          批次 <span class="mono">{{ runId }}</span>
          <template v-if="gate">
            · 基线 <span class="mono">{{ gate.baselineRunId }}</span> · 容差 ±{{ gate.tolerance }}
          </template>
        </span>
      </div>
      <span v-if="gate" class="gate-pill" :class="gate.passed ? 'pass' : 'fail'" role="status">
        <AppIcon :name="gate.passed ? 'check' : 'warning'" :size="12" />
        {{ gate.passed ? '门禁通过' : `门禁未通过（${gate.regressed.length} 项退化）` }}
      </span>
    </div>

    <div class="panel-body">
      <template v-if="gate">
        <p class="gate-summary">
          <template v-if="gate.passed">
            本批次全部 {{ gate.comparisons.length }} 项受控指标都在基线 −
            {{ gate.tolerance }} 以内， 没有确定性退化。
          </template>
          <template v-else>
            本批次有
            <b>{{ regressedRows.length }}</b> 项指标跌出容差：
            <b>{{ regressedRows.map((row) => row.label).join('、') }}</b>
            。其余 {{ gate.comparisons.length - regressedRows.length }} 项在容差内或优于基线。
          </template>
        </p>

        <div class="gate-table-wrap">
          <table class="compare-table">
            <caption class="sr-only">
              逐指标基线、本次、差值、容差与是否通过
            </caption>
            <thead>
              <tr>
                <th scope="col">指标</th>
                <th scope="col" class="num">
                  基线 <span class="th-run mono">{{ gate.baselineRunId }}</span>
                </th>
                <th scope="col" class="num">
                  本次 <span class="th-run mono">{{ runId }}</span>
                </th>
                <th scope="col" class="num">差值</th>
                <th scope="col" class="num">容差</th>
                <th scope="col">判定</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.key" :class="row.state">
                <th scope="row">
                  {{ row.label }}
                  <span class="row-metric mono">{{ row.metric }}</span>
                  <span v-if="row.note" class="row-note">{{ row.note }}</span>
                </th>
                <td class="num">{{ row.baseline }}</td>
                <td class="num current">{{ row.current }}</td>
                <td class="num" :class="row.regressed ? 'down' : row.deltaRaw > 0 ? 'up' : ''">
                  {{ row.delta }}
                </td>
                <td class="num tolerance">±{{ row.tolerance }}</td>
                <td>
                  <span class="verdict" :class="row.state">
                    {{ row.verdict }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <ul class="gate-notes">
          <li>
            退化判定：比率型指标跌破
            <span class="mono">{{ 'baseline − ' + gate.tolerance }}</span>
            ，幻觉率升破
            <span class="mono">{{ 'baseline + ' + gate.tolerance }}</span>
            。
          </li>
          <li v-if="toleranceRows.length > 0">
            {{ toleranceRows.map((row) => row.label).join('、') }}
            的变动在容差内，是采样波动而非退化，不标为失败。
          </li>
          <li>
            基线文件：<span class="mono">eval/baseline.json</span>（后端
            <span class="mono">ragwiki.eval.baseline</span> 配置项），基线批次
            <span class="mono">{{ gate.baselineRunId }}</span> 。
          </li>
          <li>
            门禁附录失败用例 {{ gate.failedCases.length }} 条，本批次失败用例 {{ failedTotal }} 条。
            <template v-if="gate.failedCases.length !== failedTotal">
              两者不一致：门禁读的是该批次落库的失败清单，报告页按 report 接口的失败清单计数。
            </template>
          </li>
        </ul>
      </template>

      <ErrorState
        v-else-if="gateError"
        :error="gateError"
        retry-text="重试门禁"
        compact
        @retry="emit('retry')"
      />
      <p v-else class="gate-loading">门禁数据加载中…</p>
    </div>
  </section>
</template>

<style scoped>
.head-left {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.gate-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 10.5px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 4px;
  line-height: 18px;
}

.gate-pill.pass {
  color: var(--green);
  background: var(--green-soft);
  border: 1px solid var(--green-line);
}

.gate-pill.fail {
  color: var(--oxblood);
  background: var(--oxblood-soft);
  border: 1px solid var(--oxblood-line);
}

.gate-summary {
  margin: 0 0 10px;
  font-size: 11.5px;
  line-height: 1.75;
  color: var(--ink-soft);
}

.gate-summary b {
  color: var(--oxblood);
}

.gate-table-wrap {
  overflow-x: auto;
}

.compare-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 11.5px;
}

.compare-table th,
.compare-table td {
  text-align: left;
  padding: 7px 9px;
  border-bottom: 1px solid var(--line-soft);
  vertical-align: top;
}

.compare-table thead th {
  font-size: 10px;
  font-weight: 500;
  color: var(--ink-faint);
  background: var(--paper);
  border-bottom: 1px solid var(--line);
  white-space: nowrap;
}

.th-run {
  display: block;
  font-size: 8.5px;
  color: var(--ink-faint);
  opacity: 0.85;
}

.compare-table tbody th {
  font-weight: 600;
  color: var(--ink-soft);
  font-size: 11px;
}

.row-metric {
  display: block;
  font-size: 9px;
  font-weight: 400;
  color: var(--ink-faint);
}

.row-note {
  display: block;
  font-size: 9px;
  font-weight: 400;
  color: var(--ink-faint);
}

.compare-table .num {
  text-align: right;
  font-family: var(--mono);
  white-space: nowrap;
}

.compare-table .current {
  color: var(--ink);
}

.compare-table .tolerance {
  color: var(--ink-faint);
}

.compare-table .up {
  color: var(--green);
}

.compare-table .down {
  color: var(--oxblood);
  font-weight: 600;
}

tr.regressed {
  background: var(--oxblood-soft);
}

tr.regressed th,
tr.regressed td {
  border-bottom-color: var(--oxblood-line);
}

tr.regressed .current {
  color: var(--oxblood);
  font-weight: 700;
}

.verdict {
  font-size: 10px;
  line-height: 1.5;
  display: inline-block;
  padding: 1px 5px;
  border-radius: 3px;
  border: 1px solid transparent;
}

.verdict.regressed {
  color: var(--oxblood);
  background: var(--paper-raised);
  border-color: var(--oxblood-line);
  font-weight: 600;
}

.verdict.tolerance {
  color: var(--ink-mute);
  background: var(--paper);
  border-color: var(--line);
}

.verdict.improved {
  color: var(--green);
  background: var(--green-soft);
  border-color: var(--green-line);
}

.verdict.ok {
  color: var(--ink-mute);
}

.gate-notes {
  list-style: none;
  margin: 10px 0 0;
  padding: 9px 0 0;
  border-top: 1px solid var(--line-soft);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.gate-notes li {
  position: relative;
  padding-left: 10px;
  font-size: 9.5px;
  line-height: 1.7;
  color: var(--ink-faint);
}

.gate-notes li::before {
  content: '-';
  position: absolute;
  left: 0;
  color: var(--line-strong);
}

.gate-loading {
  margin: 0;
  font-size: 11px;
  color: var(--ink-faint);
}

@media (max-width: 760px) {
  .compare-table {
    font-size: 10.5px;
  }

  .compare-table th,
  .compare-table td {
    padding: 6px 6px;
  }

  .row-note {
    display: none;
  }
}
</style>
