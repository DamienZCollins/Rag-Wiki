<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import {
  getGate,
  importCases,
  listRuns,
  runEval,
  writeBaseline,
  type BaselineReport,
  type CaseImportReport,
  type EvalRun,
  type GateReport,
} from '@/api/eval'
import { useToast } from '@/composables/useToast'
import OpsTask from './OpsTask.vue'
import OpsResultMeta from './OpsResultMeta.vue'
import { formatDuration, formatInt, formatPercent, formatSigned } from './format'
import { useOpsAction } from './useOpsAction'
import { useOpsConfirm } from './useOpsConfirm'
import type { MetaItem } from './types'

/**
 * 评测与门禁：用例导入、跑批、基线固化、门禁查询。
 *
 * 跑批是同步接口，一批上百条要十几分钟；页面只展示真实等待时长，
 * 不伪造进度，也不把历史批次的指标当成本次结果。
 */

type CaseType = 'POLICY' | 'ORDER' | 'REFUSAL' | 'ADVERSARIAL' | 'ALL'

const CASE_TYPE_OPTIONS: { value: CaseType; label: string }[] = [
  { value: 'ALL', label: '全部' },
  { value: 'POLICY', label: 'POLICY' },
  { value: 'ORDER', label: 'ORDER' },
  { value: 'REFUSAL', label: 'REFUSAL' },
  { value: 'ADVERSARIAL', label: 'ADVERSARIAL' },
]

const toast = useToast()
const confirm = useOpsConfirm()

const importAction = useOpsAction<CaseImportReport>()
const runAction = useOpsAction<EvalRun>()
const baselineAction = useOpsAction<BaselineReport>()
const gateAction = useOpsAction<GateReport>()

const caseType = ref<CaseType>('ALL')
const runLabel = ref('')
const baselineRunId = ref('')
const gateRunId = ref('')
const recentRuns = ref<EvalRun[]>([])
const runsLoading = ref(false)

async function loadRecentRuns(silent = false) {
  if (runsLoading.value) return
  runsLoading.value = true
  try {
    const runs = await listRuns()
    recentRuns.value = runs
    const latest = runs[0]?.runId ?? ''
    if (!baselineRunId.value) baselineRunId.value = latest
    if (!gateRunId.value) gateRunId.value = latest
    if (!silent) toast.info(`已载入 ${runs.length} 个历史批次`)
  } catch (error) {
    if (!silent) {
      toast.error(error instanceof Error ? error.message : '历史批次载入失败')
    }
  } finally {
    runsLoading.value = false
  }
}

onMounted(() => {
  void loadRecentRuns(true)
})

/* ---------------- 用例导入 ---------------- */

async function onImport() {
  if (importAction.phase.value === 'running') return
  const accepted = await confirm.ask({
    title: '确认导入评测用例？',
    message: '把 eval/cases/*.jsonl 全量导入评测用例表。',
    impact:
      '影响范围：先清空旧的用例快照再写入，正在跑的批次不受影响但后续批次会按新用例集执行。预计耗时：秒级。',
    confirmText: '开始导入',
  })
  if (!accepted) return
  const report = await importAction.run((signal) => importCases(signal))
  if (report) toast.success(`用例导入完成：${report.imported} 条`)
}

const importMeta = computed<MetaItem[]>(() => {
  const report = importAction.outcome.value?.data
  if (!report) return []
  return [
    { label: '导入用例数', value: formatInt(report.imported), tone: 'ok' },
    { label: '源文件数', value: formatInt(report.files.length) },
  ]
})

const importLists = computed(() => {
  const report = importAction.outcome.value?.data
  if (!report) return []
  return [
    {
      label: '按类型分布',
      values: Object.entries(report.byType).map(([type, count]) => `${type} · ${count}`),
    },
    { label: '源文件', values: report.files },
  ]
})

/* ---------------- 跑批 ---------------- */

async function onRun() {
  if (runAction.phase.value === 'running') return
  const types = caseType.value === 'ALL' ? [] : [caseType.value]
  const scope = caseType.value === 'ALL' ? '全部用例类型' : `仅 ${caseType.value} 类型用例`
  const accepted = await confirm.ask({
    title: '确认跑一批评测？',
    message: `批次标签：${runLabel.value.trim() || '未命名批次'}；范围：${scope}。`,
    impact:
      '影响范围：逐条调用真实模型与检索链路，写入新的批次、逐条结果与归因，并可能被后续基线固化引用；不修改 Wiki 与索引。预计耗时：全部用例 15~20 分钟，单类型按用例数折算。同步接口，返回前页面必须保持打开。',
    confirmText: '开始跑批',
    tone: 'danger',
  })
  if (!accepted) return
  const run = await runAction.run((signal) =>
    runEval({ label: runLabel.value.trim() || null, caseTypes: types }, signal),
  )
  if (run) {
    baselineRunId.value = run.runId
    gateRunId.value = run.runId
    void loadRecentRuns(true)
    toast.success(`批次完成：${run.runId}`)
  }
}

const runMeta = computed<MetaItem[]>(() => {
  const run = runAction.outcome.value?.data
  if (!run) return []
  return [
    { label: 'runId', value: run.runId, mono: true, wide: true },
    { label: '批次标签', value: run.label, mono: false, wide: true },
    { label: '用例类型', value: run.caseTypes, mono: true },
    {
      label: '状态',
      value: run.status,
      tone: run.status === 'COMPLETED' ? 'ok' : 'warn',
    },
    { label: '用例数', value: formatInt(run.caseCount) },
    { label: '通过', value: formatInt(run.passed), tone: 'ok' },
    {
      label: '失败',
      value: formatInt(run.failed),
      tone: run.failed > 0 ? 'bad' : undefined,
    },
    { label: '通过率', value: formatPercent(run.metrics?.passRate) },
    { label: '批次耗时', value: formatDuration(run.latencyMs) },
  ]
})

const runAttribution = computed(() => {
  const run = runAction.outcome.value?.data
  if (!run?.attribution) return []
  return Object.entries(run.attribution)
    .filter(([, count]) => typeof count === 'number' && count > 0)
    .map(([layer, count]) => ({ layer, count: count as number }))
})

/* ---------------- 基线固化 ---------------- */

async function onWriteBaseline() {
  if (baselineAction.phase.value === 'running') return
  const runId = baselineRunId.value.trim()
  if (!runId) {
    toast.error('先填要固化为基线的 runId')
    return
  }
  const current = currentBaselineRunId.value
  const accepted = await confirm.ask({
    title: '确认把该批次固化为基线？',
    message: current ? `新基线：${runId}；当前基线：${current}（将被覆盖）` : `新基线：${runId}`,
    impact:
      `影响范围：直接覆盖后端文件 backend/eval/baseline.json 里现有的基线，` +
      `${current ? `当前基线 ${current} 的指标不保留、无法在页面上回滚；` : ''}` +
      '此后所有批次的门禁判定都改为与新基线比较。预计耗时：秒级。',
    confirmText: '覆盖并写入基线',
    tone: 'danger',
  })
  if (!accepted) return
  const report = await baselineAction.run((signal) => writeBaseline(runId, signal))
  if (report) toast.success(`基线已固化：${report.runId}`)
}

/**
 * 当前基线批次：只能从只读的门禁查询结果里得知（后端没有单独的「读基线」接口），
 * 未查询过门禁时为空，界面上如实写「未知」，不猜。
 */
const currentBaselineRunId = computed<string | null>(
  () => gateAction.outcome.value?.data.baselineRunId ?? null,
)

const baselineMeta = computed<MetaItem[]>(() => {
  const report = baselineAction.outcome.value?.data
  if (!report) return []
  return [
    { label: 'runId', value: report.runId, mono: true, wide: true },
    { label: '批次标签', value: report.label || '—', mono: false, wide: true },
    { label: '受控指标数', value: formatInt(Object.keys(report.metrics).length) },
  ]
})

const baselineLists = computed(() => {
  const report = baselineAction.outcome.value?.data
  if (!report) return []
  return [
    {
      label: '固化指标（比率）',
      values: Object.entries(report.metrics).map(([metric, value]) => `${metric} = ${value}`),
    },
  ]
})

/* ---------------- 门禁查询 ---------------- */

async function onQueryGate() {
  if (gateAction.phase.value === 'running') return
  const runId = gateRunId.value.trim()
  if (!runId) {
    toast.error('先填要查询门禁的 runId')
    return
  }
  // 只读查询，不需要二次确认
  const report = await gateAction.run((signal) => getGate(runId, signal))
  if (report) {
    toast.info(
      report.passed ? '门禁通过' : `门禁未通过：${report.regressed.join('、') || '存在失败用例'}`,
    )
  }
}

const gateMeta = computed<MetaItem[]>(() => {
  const report = gateAction.outcome.value?.data
  if (!report) return []
  return [
    {
      label: '门禁结论',
      value: report.passed ? '通过' : '未通过',
      tone: report.passed ? 'ok' : 'bad',
    },
    { label: '容差', value: report.tolerance.toString() },
    { label: '基线 runId', value: report.baselineRunId, mono: true, wide: true },
    {
      label: '退化指标',
      value: report.regressed.length > 0 ? report.regressed.join('、') : '无',
      mono: false,
      tone: report.regressed.length > 0 ? 'bad' : 'ok',
      wide: true,
    },
    { label: '失败用例数', value: formatInt(report.failedCases.length) },
  ]
})

const gateLists = computed(() => {
  const report = gateAction.outcome.value?.data
  if (!report) return []
  return [{ label: '失败用例', values: report.failedCases }]
})

const runIdSuggestions = computed(() =>
  recentRuns.value.slice(0, 20).map((run) => ({
    value: run.runId,
    label: `${run.runId} · ${run.label}`,
  })),
)
</script>

<template>
  <div class="eval-panel">
    <datalist id="ops-run-ids">
      <option v-for="item in runIdSuggestions" :key="item.value" :value="item.value">
        {{ item.label }}
      </option>
    </datalist>

    <div class="task-grid">
      <OpsTask
        title="用例导入"
        description="把 eval/cases/*.jsonl 导入评测用例表。源文件是唯一事实源，改了用例必须重新导入才生效。"
        impact="先清空旧的用例快照再写入，后续批次按新用例集执行。"
        cost="秒级"
        :running="importAction.phase.value === 'running'"
        :elapsed-ms="importAction.elapsedMs.value"
        :failure="importAction.failure.value"
        :outcome="importAction.outcome.value"
        result-title="导入返回"
        idle-text="尚未导入用例"
        @retry="onImport"
      >
        <template #actions>
          <button
            class="btn sm"
            type="button"
            :disabled="importAction.phase.value === 'running'"
            @click="onImport"
          >
            <AppIcon name="download" :size="14" />
            {{ importAction.phase.value === 'running' ? '导入中…' : '导入用例' }}
          </button>
        </template>

        <template #result>
          <OpsResultMeta :items="importMeta" :lists="importLists" />
        </template>
      </OpsTask>

      <OpsTask
        title="跑批"
        description="按用例类型跑一批评测。同步接口：后端不返回就是还没跑完，页面只显示真实的等待时长。"
        impact="逐条调用真实模型与检索链路，写新批次、逐条结果与归因；不改 Wiki 与索引。"
        cost="全部用例 15~20 分钟；单类型按用例数折算"
        dangerous
        :running="runAction.phase.value === 'running'"
        :elapsed-ms="runAction.elapsedMs.value"
        :failure="runAction.failure.value"
        :outcome="runAction.outcome.value"
        result-title="批次返回"
        idle-text="尚未跑批"
        @retry="onRun"
      >
        <template #controls>
          <div class="run-controls">
            <label class="ops-field">
              <span>批次标签</span>
              <input
                v-model="runLabel"
                class="ops-input"
                type="text"
                placeholder="例如 wiki 索引 + 双通道"
                :disabled="runAction.phase.value === 'running'"
              />
            </label>
            <div
              class="seg"
              role="radiogroup"
              aria-label="用例类型"
              :aria-disabled="runAction.phase.value === 'running'"
            >
              <button
                v-for="option in CASE_TYPE_OPTIONS"
                :key="option.value"
                type="button"
                role="radio"
                :aria-checked="caseType === option.value"
                :aria-selected="caseType === option.value"
                :disabled="runAction.phase.value === 'running'"
                @click="caseType = option.value"
              >
                {{ option.label }}
              </button>
            </div>
            <p class="ops-hint">同步接口，返回前不会有进度；跑批过程中请不要关闭页面。</p>
          </div>
        </template>

        <template #actions>
          <button
            class="btn ox sm"
            type="button"
            :disabled="runAction.phase.value === 'running'"
            @click="onRun"
          >
            <AppIcon name="bolt" :size="14" />
            {{ runAction.phase.value === 'running' ? '跑批中…' : '开始跑批' }}
          </button>
          <button
            class="btn ghost sm"
            type="button"
            :disabled="runsLoading"
            @click="loadRecentRuns()"
          >
            <AppIcon name="refresh" :size="14" />
            刷新历史批次
          </button>
        </template>

        <template #result>
          <OpsResultMeta :items="runMeta" />
          <div v-if="runAttribution.length > 0" class="attr-row">
            <span class="attr-row__label">归因分布</span>
            <span v-for="item in runAttribution" :key="item.layer" class="tag amber">
              {{ item.layer }} · {{ item.count }}
            </span>
          </div>
          <p class="ops-hint">runId 已填入下方「基线固化」与「门禁查询」，可直接接着操作。</p>
        </template>
      </OpsTask>

      <OpsTask
        title="基线固化"
        description="把某个批次的比率型指标固化为回归门禁的对照。"
        impact="直接覆盖后端 backend/eval/baseline.json，原基线指标不可恢复，无法在页面上回滚；此后所有批次的门禁都与新基线比较。"
        cost="秒级"
        dangerous
        :running="baselineAction.phase.value === 'running'"
        :elapsed-ms="baselineAction.elapsedMs.value"
        :failure="baselineAction.failure.value"
        :outcome="baselineAction.outcome.value"
        result-title="基线返回"
        idle-text="尚未固化基线"
        @retry="onWriteBaseline"
      >
        <template #controls>
          <label class="ops-field">
            <span>runId</span>
            <input
              v-model="baselineRunId"
              class="ops-input mono"
              type="text"
              list="ops-run-ids"
              placeholder="选择或填入 runId"
              :disabled="baselineAction.phase.value === 'running'"
            />
          </label>
          <p class="ops-hint">
            当前基线：
            <span class="mono">{{ currentBaselineRunId ?? '未知（先查一次门禁即可得知）' }}</span>
            <br />
            最近批次：{{ recentRuns.length }} 个（只读接口载入，仅用于填充 runId）
          </p>
        </template>

        <template #actions>
          <button
            class="btn ox sm"
            type="button"
            :disabled="baselineAction.phase.value === 'running' || !baselineRunId.trim()"
            @click="onWriteBaseline"
          >
            <AppIcon name="check" :size="14" />
            {{ baselineAction.phase.value === 'running' ? '写入中…' : '固化为基线' }}
          </button>
        </template>

        <template #result>
          <OpsResultMeta :items="baselineMeta" :lists="baselineLists" />
        </template>
      </OpsTask>

      <OpsTask
        title="门禁查询"
        description="拿基线与指定批次比较，只比率型指标，超容差即判不通过。只读接口。"
        :running="gateAction.phase.value === 'running'"
        :elapsed-ms="gateAction.elapsedMs.value"
        :failure="gateAction.failure.value"
        :outcome="gateAction.outcome.value"
        result-title="门禁返回"
        idle-text="尚未查询门禁"
        @retry="onQueryGate"
      >
        <template #controls>
          <label class="ops-field">
            <span>runId</span>
            <input
              v-model="gateRunId"
              class="ops-input mono"
              type="text"
              list="ops-run-ids"
              placeholder="选择或填入 runId"
              :disabled="gateAction.phase.value === 'running'"
              @keyup.enter="onQueryGate"
            />
          </label>
        </template>

        <template #actions>
          <button
            class="btn sm"
            type="button"
            :disabled="gateAction.phase.value === 'running' || !gateRunId.trim()"
            @click="onQueryGate"
          >
            <AppIcon name="search" :size="14" />
            {{ gateAction.phase.value === 'running' ? '查询中…' : '查询门禁' }}
          </button>
        </template>

        <template #result>
          <OpsResultMeta :items="gateMeta" :lists="gateLists" />
          <table class="gate-table">
            <caption class="sr-only">
              门禁指标对比
            </caption>
            <thead>
              <tr>
                <th scope="col">指标</th>
                <th scope="col" class="num">基线</th>
                <th scope="col" class="num">本批</th>
                <th scope="col" class="num">差值</th>
                <th scope="col">判定</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="comparison in gateAction.outcome.value?.data.comparisons ?? []"
                :key="comparison.metric"
              >
                <td class="mono">{{ comparison.metric }}</td>
                <td class="num mono">{{ comparison.baseline.toFixed(4) }}</td>
                <td class="num mono">{{ comparison.current.toFixed(4) }}</td>
                <td class="num mono" :class="comparison.delta >= 0 ? 'up' : 'down'">
                  {{ formatSigned(comparison.delta) }}
                </td>
                <td>
                  <span class="tag" :class="comparison.regressed ? 'ox' : 'green'">
                    {{ comparison.regressed ? '退化' : '正常' }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </template>
      </OpsTask>
    </div>

    <ConfirmDialog
      :open="confirm.open.value"
      :title="confirm.options.value.title"
      :message="confirm.options.value.message"
      :impact="confirm.options.value.impact"
      :tone="confirm.options.value.tone ?? 'normal'"
      :confirm-text="confirm.options.value.confirmText ?? '确认'"
      :cancel-text="confirm.options.value.cancelText ?? '取消'"
      @confirm="confirm.accept"
      @cancel="confirm.cancel"
    />
  </div>
</template>

<style scoped>
.eval-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.task-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}

.run-controls {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1 1 240px;
  min-width: 0;
}

.run-controls .seg {
  margin-top: 0;
}

.ops-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.ops-field > span {
  font-size: 10.5px;
  color: var(--ink-faint);
}

.ops-input {
  height: 30px;
  padding: 0 9px;
  border: 1px solid var(--line);
  border-radius: var(--r-s);
  background: var(--paper-raised);
  font-size: 11.5px;
  color: var(--ink);
  min-width: 0;
}

.ops-hint {
  margin: 0;
  font-size: 10.5px;
  line-height: 1.6;
  color: var(--ink-faint);
}

.attr-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
}

.attr-row__label {
  font-size: 10.5px;
  color: var(--ink-faint);
}

.gate-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 11px;
  background: var(--paper);
  border: 1px solid var(--line-soft);
  border-radius: var(--r-s);
  overflow: hidden;
}

.gate-table th {
  text-align: left;
  font-size: 10px;
  font-weight: 500;
  color: var(--ink-faint);
  padding: 6px 8px;
  border-bottom: 1px solid var(--line);
}

.gate-table td {
  padding: 6px 8px;
  border-bottom: 1px solid var(--line-soft);
  color: var(--ink-soft);
}

.gate-table tr:last-child td {
  border-bottom: 0;
}

.gate-table .num {
  text-align: right;
  font-family: var(--mono);
}

.gate-table .up {
  color: var(--green);
}

.gate-table .down {
  color: var(--oxblood);
}

@media (max-width: 1080px) {
  .task-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
