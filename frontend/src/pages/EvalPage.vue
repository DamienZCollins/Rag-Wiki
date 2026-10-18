<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppLayout from '@/layouts/AppLayout.vue'
import AppIcon from '@/components/AppIcon.vue'
import ErrorState from '@/components/ErrorState.vue'
import SkeletonBlock from '@/components/SkeletonBlock.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useRunList } from '@/features/eval/useRunList'
import { countUnattributed, mergeFailures, useEvalBundle } from '@/features/eval/useEvalBundle'
import {
  buildChannelGroup,
  buildIndexGroup,
  indexArmOf,
  INDEX_ARM_LABEL,
} from '@/features/eval/compare'
import { useFailureFilter, type LayerFilter } from '@/features/eval/failureFilter'
import { formatDateTime, formatDuration, formatPercent } from '@/features/eval/format'
import { sumLayers, type LayerKey } from '@/features/eval/layers'
import RunSelector from '@/features/eval/RunSelector.vue'
import MetricCards from '@/features/eval/MetricCards.vue'
import CompareChart from '@/features/eval/CompareChart.vue'
import AttributionChart from '@/features/eval/AttributionChart.vue'
import GatePanel from '@/features/eval/GatePanel.vue'
import ConfigSnapshot from '@/features/eval/ConfigSnapshot.vue'
import FailureList from '@/features/eval/FailureList.vue'

/**
 * 评测与归因主页面。
 *
 * 一个批次一屏：批次列表 → 报告头 → 指标 → 对比实验 → 五层归因 → 门禁 → 配置快照 → 失败摘要。
 * 页面上每个数字都归属一个 runId：标题区、图表图例、面板头各标一次。
 * 失败用例只放摘要，全量明细在 /eval/:runId/cases。
 */

const route = useRoute()
const router = useRouter()

const requestedRunId = computed<string | null>(() => {
  const raw = route.params.runId
  const value = Array.isArray(raw) ? raw[0] : raw
  return value ? value : null
})

const {
  runs,
  loading: runsLoading,
  error: runsError,
  reload: reloadRuns,
  latestCompleted,
  missingRunId,
} = useRunList(requestedRunId)

/** 当前生效的批次：URL 指定优先，否则最近一批已完成 */
const activeRunId = computed<string | null>(
  () => requestedRunId.value ?? latestCompleted.value?.runId ?? null,
)

const {
  bundle,
  loading: bundleLoading,
  error: bundleError,
  reload: reloadBundle,
} = useEvalBundle(activeRunId)

const report = computed(() => bundle.value?.report ?? null)
const metrics = computed(() => report.value?.metrics ?? null)
const run = computed(() => report.value?.run ?? null)

const failures = computed(() =>
  report.value ? mergeFailures(report.value.failures, bundle.value?.results ?? []) : [],
)

const unattributed = computed(() => (report.value ? countUnattributed(report.value.failures) : 0))
const failedTotal = computed(() => report.value?.metrics.failed ?? failures.value.length)

const { layer, keyword, visible, layerCounts, matchedTotal, setLayer } = useFailureFilter(failures)

const indexGroup = computed(() => buildIndexGroup(runs.value, run.value))
const channelGroup = computed(() => buildChannelGroup(runs.value, run.value))
const compareGroups = computed(() =>
  [indexGroup.value, channelGroup.value].filter((group) => group !== null),
)

/** 凑不齐两臂时说明原因，而不是静默少一块图 */
const compareGaps = computed(() => {
  const gaps: string[] = []
  if (!indexGroup.value) {
    gaps.push('批次列表里缺少「原始文档索引」或「Wiki 页面索引」的另一臂')
  }
  if (!channelGroup.value) {
    gaps.push('批次列表里缺少「双通道」或「纯 RAG」的另一臂')
  }
  return gaps.join('；')
})

const compareGapHint = computed(
  () =>
    `${compareGaps.value}。对比要求两臂只改一个变量，凑不齐时不画图，避免拿不可比的数字下结论。`,
)

const SUMMARY_LIMIT = 8

const loading = computed(() => runsLoading.value || bundleLoading.value)
const loadError = computed(() => runsError.value ?? bundleError.value)
const hasData = computed(
  () => report.value !== null && metrics.value !== null && run.value !== null,
)

const statusHint = computed(() => {
  const current = run.value
  if (!current) return ''
  if (current.status === 'RUNNING') return '该批次仍在运行，指标可能随时变化'
  if (current.status === 'INTERRUPTED') return '该批次被中断，指标只覆盖已跑完的用例'
  return ''
})

const missingRunError = computed(() =>
  missingRunId.value
    ? new Error(
        `批次 ${missingRunId.value} 不在最近 50 条批次列表里（后端只返回最近 50 条），要么确认 runId，要么从列表里选一批`,
      )
    : null,
)

const selectedLayer = computed(() => (layer.value === 'ALL' ? null : layer.value))

/** 未指定批次时把 URL 补成最近一批，保证刷新、分享与当前视图一致 */
watch(
  [latestCompleted, requestedRunId],
  ([latest, requested]) => {
    if (!requested && latest) {
      void router.replace({ name: 'eval-run', params: { runId: latest.runId } })
    }
  },
  { immediate: true },
)

function selectRun(runId: string) {
  if (runId === activeRunId.value) return
  void router.push({ name: 'eval-run', params: { runId } })
}

function openCases() {
  if (!activeRunId.value) return
  void router.push({ name: 'eval-cases', params: { runId: activeRunId.value } })
}

function retryAll() {
  reloadRuns()
  reloadBundle()
}

/** 归因图点击某一层：null 表示取消筛选，回到「全部」 */
function pickLayer(next: LayerKey | null) {
  setLayer(next ?? 'ALL')
}

/** 失败列表的筛选条直接给 LayerFilter（含 'ALL'） */
function setLayerFilter(next: LayerFilter) {
  setLayer(next)
}

function setKeyword(value: string) {
  keyword.value = value
}
</script>

<template>
  <AppLayout>
    <template #context>
      <span v-if="run">
        {{ run.label }} · <span class="mono">{{ run.runId }}</span> · {{ run.caseCount }} 条 ·
        {{ formatDateTime(run.startedAt) }}
      </span>
    </template>

    <div class="eval-scroll">
      <div class="eval-inner">
        <header class="eval-head">
          <div class="head-main">
            <h1 class="eval-title">评测与归因</h1>
            <div class="eval-sub">
              <template v-if="run">
                <span class="mono strong">{{ run.runId }}</span>
                <span class="dot-sep" />
                <span>{{ run.label }}</span>
                <span class="dot-sep" />
                <span>{{ formatDateTime(run.startedAt) }}</span>
                <span class="dot-sep" />
                <span>{{ run.caseCount }} 条用例</span>
                <span class="dot-sep" />
                <span>索引源：{{ INDEX_ARM_LABEL[indexArmOf(run)] }}</span>
                <span class="dot-sep" />
                <span>{{ run.config.fastPathEnabled ? '双通道' : '纯 RAG' }}</span>
                <span class="dot-sep" />
                <span>全量耗时 {{ formatDuration(run.latencyMs) }}</span>
              </template>
              <span v-else-if="runsLoading">正在读取批次列表…</span>
              <span v-else>暂无可用批次</span>
            </div>
          </div>
          <div class="eval-actions">
            <button class="btn sm" type="button" :disabled="loading" @click="retryAll">
              <AppIcon name="refresh" :size="13" />
              刷新
            </button>
            <button
              class="btn primary sm"
              type="button"
              :disabled="!activeRunId"
              @click="openCases"
            >
              失败用例明细
              <AppIcon name="arrow-right" :size="13" />
            </button>
          </div>
        </header>

        <p v-if="statusHint" class="status-hint" role="status">{{ statusHint }}</p>

        <ErrorState v-if="loadError" :error="loadError" retry-text="重新加载" @retry="retryAll" />

        <ErrorState
          v-else-if="missingRunError"
          :error="missingRunError"
          retry-text="回到最近一批"
          @retry="router.push({ name: 'eval' })"
        />

        <template v-else>
          <RunSelector
            :runs="runs"
            :selected-id="activeRunId ?? ''"
            :loading="runsLoading"
            @select="selectRun"
          />

          <div v-if="loading && !hasData" class="loading-stack">
            <SkeletonBlock height="54px" />
            <SkeletonBlock :lines="3" height="42px" />
            <SkeletonBlock :lines="2" height="120px" />
          </div>

          <EmptyState
            v-else-if="!hasData && !runsLoading"
            title="还没有可展示的评测批次"
            description="先在后端跑一次评测（POST /api/eval/run），批次完成后这里会显示指标、五层归因与回归门禁。"
          />

          <template v-else-if="hasData && run && metrics && report">
            <MetricCards
              :run-id="run.runId"
              :metrics="metrics"
              :gate="bundle?.gate ?? null"
              :case-count="run.caseCount"
            />

            <CompareChart
              v-if="compareGroups.length > 0"
              :groups="compareGroups"
              @jump="selectRun"
            />
            <section v-else class="panel">
              <div class="panel-head">
                <h2 class="panel-title">对比实验</h2>
                <span class="panel-meta">
                  批次 <span class="mono">{{ run.runId }}</span>
                </span>
              </div>
              <div class="panel-body">
                <EmptyState
                  compact
                  title="当前列表里凑不齐对比的两臂"
                  :description="compareGapHint"
                />
              </div>
            </section>

            <AttributionChart
              :run-id="run.runId"
              :attribution="report.attribution"
              :failed-total="failedTotal"
              :unattributed="unattributed"
              :selected="selectedLayer"
              @pick="pickLayer"
            />

            <GatePanel
              :run-id="run.runId"
              :gate="bundle?.gate ?? null"
              :gate-error="bundle?.gateError ?? null"
              :failed-total="failedTotal"
              @retry="reloadBundle"
            />

            <ConfigSnapshot :run="run" />

            <FailureList
              :run-id="run.runId"
              :rows="visible"
              :total="matchedTotal"
              :layer-counts="layerCounts"
              :layer="layer"
              :keyword="keyword"
              :limit="SUMMARY_LIMIT"
              @update:layer="setLayerFilter"
              @update:keyword="setKeyword"
              @jump="openCases"
            />

            <footer class="eval-foot">
              <p>
                本页全部数字取自批次 <span class="mono">{{ run.runId }}</span>
                的接口返回值（report / results / gate）。比率统一 4 位小数，
                分位数用最近秩法，延迟按通道分开统计；口径见 docs/评测指标口径.md。
              </p>
              <p>
                归因五层合计 <span class="mono">{{ sumLayers(report.attribution) }}</span> / 失败
                <span class="mono">{{ failedTotal }}</span> 条
                <template v-if="unattributed > 0">
                  ，其中 {{ unattributed }} 条未归因（后端返回 NONE）
                </template>
                ；通过率 <span class="mono">{{ formatPercent(metrics.passRate) }}</span> （{{
                  metrics.passed
                }}/{{ metrics.caseCount }}）。
              </p>
            </footer>
          </template>
        </template>
      </div>
    </div>
  </AppLayout>
</template>

<style scoped>
.eval-scroll {
  overflow: auto;
  padding: 20px clamp(18px, 2.6vw, 32px) 36px;
  scrollbar-width: thin;
  background: var(--paper-raised);
  min-height: 0;
}

.eval-inner {
  max-width: 1120px;
  margin: 0 auto;
}

.eval-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.head-main {
  min-width: 0;
}

.eval-title {
  font-family: var(--serif);
  font-size: 23px;
  font-weight: 700;
  margin: 0;
  line-height: 1.35;
}

.eval-sub {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
  font-size: 11px;
  color: var(--ink-mute);
  margin-top: 4px;
}

.eval-sub .strong {
  color: var(--ink-soft);
}

.eval-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  flex: none;
}

.status-hint {
  margin: 0 0 12px;
  padding: 7px 10px;
  font-size: 11px;
  color: var(--amber);
  background: var(--amber-soft);
  border: 1px solid var(--amber-line);
  border-radius: var(--r-s);
}

.loading-stack {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.eval-foot {
  margin-top: 4px;
  padding: 12px 2px 0;
  border-top: 1px solid var(--line-soft);
}

.eval-foot p {
  margin: 0 0 5px;
  font-size: 10px;
  line-height: 1.75;
  color: var(--ink-faint);
}

@media (max-width: 1080px) {
  .eval-scroll {
    padding: 16px 16px 28px;
  }
}

@media (max-width: 760px) {
  .eval-scroll {
    padding: 14px 11px 24px;
  }

  .eval-head {
    flex-direction: column;
    gap: 9px;
  }

  .eval-title {
    font-size: 20px;
  }

  .eval-sub {
    font-size: 10px;
    gap: 5px;
  }

  .eval-actions {
    width: 100%;
  }

  .eval-actions .btn {
    flex: 1;
  }
}
</style>
