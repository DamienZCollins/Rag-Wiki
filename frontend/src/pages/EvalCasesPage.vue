<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppLayout from '@/layouts/AppLayout.vue'
import AppIcon from '@/components/AppIcon.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'
import SkeletonBlock from '@/components/SkeletonBlock.vue'
import { useRunList } from '@/features/eval/useRunList'
import { countUnattributed, mergeFailures, useEvalBundle } from '@/features/eval/useEvalBundle'
import { useFailureFilter } from '@/features/eval/failureFilter'
import { isLayerKey, sumLayers, type LayerKey } from '@/features/eval/layers'
import { formatDateTime } from '@/features/eval/format'
import FailureList from '@/features/eval/FailureList.vue'

/**
 * 失败用例明细页。
 *
 * 与报告页共用同一份批次缓存，来回切换不重复请求。
 * 归因层筛选写进 query 参数 `layer`，从报告页点某一层跳过来、刷新页面都保持一致。
 */

const route = useRoute()
const router = useRouter()

const runId = computed<string | null>(() => {
  const raw = route.params.runId
  const value = Array.isArray(raw) ? raw[0] : raw
  return value ? value : null
})

const {
  runs: _runs,
  loading: runsLoading,
  error: runsError,
  reload: reloadRuns,
  missingRunId,
} = useRunList(runId)

const {
  bundle,
  loading: bundleLoading,
  error: bundleError,
  reload: reloadBundle,
} = useEvalBundle(runId)

const report = computed(() => bundle.value?.report ?? null)
const run = computed(() => report.value?.run ?? null)

const failures = computed(() =>
  report.value ? mergeFailures(report.value.failures, bundle.value?.results ?? []) : [],
)

const unattributed = computed(() => (report.value ? countUnattributed(report.value.failures) : 0))

function readLayerFromQuery(): LayerKey | 'ALL' {
  const raw = route.query.layer
  const value = Array.isArray(raw) ? raw[0] : raw
  return isLayerKey(value) ? value : 'ALL'
}

const { layer, keyword, visible, layerCounts, matchedTotal, setLayer } = useFailureFilter(
  failures,
  readLayerFromQuery(),
)

// 筛选层写回 URL，刷新与分享都还原同一个视图
watch(layer, (value) => {
  const current = Array.isArray(route.query.layer) ? route.query.layer[0] : route.query.layer
  const next = value === 'ALL' ? undefined : value
  if ((current ?? undefined) === next) return
  void router.replace({
    name: 'eval-cases',
    params: { runId: runId.value ?? '' },
    query: next ? { layer: next } : {},
  })
})

const layerSum = computed(() => (report.value ? sumLayers(report.value.attribution) : 0))
const failedTotal = computed(() => report.value?.metrics.failed ?? failures.value.length)

const loading = computed(() => runsLoading.value || bundleLoading.value)
const loadError = computed(() => runsError.value ?? bundleError.value)

const missingRunError = computed(() =>
  missingRunId.value
    ? new Error(
        `批次 ${missingRunId.value} 不在最近 50 条批次列表里（后端只返回最近 50 条），请确认 runId`,
      )
    : null,
)

function backToReport() {
  if (!runId.value) {
    void router.push({ name: 'eval' })
    return
  }
  void router.push({ name: 'eval-run', params: { runId: runId.value } })
}

function retryAll() {
  reloadRuns()
  reloadBundle()
}

function setKeyword(value: string) {
  keyword.value = value
}
</script>

<template>
  <AppLayout>
    <template #context>
      <span v-if="run">
        失败用例明细 · <span class="mono">{{ run.runId }}</span> · {{ failedTotal }} 条失败 /
        {{ run.caseCount }} 条
      </span>
    </template>

    <div class="cases-scroll">
      <div class="cases-inner">
        <header class="cases-head">
          <div class="head-main">
            <h1 class="cases-title">失败用例明细</h1>
            <div class="cases-sub">
              <template v-if="run">
                <span class="mono strong">{{ run.runId }}</span>
                <span class="dot-sep" />
                <span>{{ run.label }}</span>
                <span class="dot-sep" />
                <span>{{ run.caseCount }} 条用例 · 失败 {{ failedTotal }} 条</span>
                <span class="dot-sep" />
                <span>归因五层合计 {{ layerSum }}</span>
                <template v-if="unattributed > 0">
                  <span class="dot-sep" />
                  <span class="warn">未归因 {{ unattributed }} 条</span>
                </template>
                <span class="dot-sep" />
                <span>{{ formatDateTime(run.startedAt) }}</span>
              </template>
              <span v-else-if="runsLoading">正在读取批次…</span>
            </div>
          </div>
          <div class="cases-actions">
            <button class="btn sm" type="button" :disabled="loading" @click="retryAll">
              <AppIcon name="refresh" :size="13" />
              刷新
            </button>
            <button class="btn sm" type="button" @click="backToReport">
              <AppIcon name="arrow-left" :size="13" />
              返回报告
            </button>
          </div>
        </header>

        <ErrorState v-if="loadError" :error="loadError" retry-text="重新加载" @retry="retryAll" />

        <ErrorState
          v-else-if="missingRunError"
          :error="missingRunError"
          retry-text="返回报告页"
          @retry="router.push({ name: 'eval' })"
        />

        <div v-else-if="loading && !report" class="loading-stack">
          <SkeletonBlock :lines="2" height="40px" />
          <SkeletonBlock :lines="5" height="34px" />
        </div>

        <EmptyState
          v-else-if="!report"
          title="没有找到该批次的报告"
          description="确认 runId 后重试，或回到报告页重新选择批次。"
          action-text="返回报告页"
          @action="backToReport"
        />

        <template v-else-if="run">
          <p class="cases-note">
            归因五层合计 <span class="mono">{{ layerSum }}</span> / 失败
            <span class="mono">{{ failedTotal }}</span> 条
            <template v-if="unattributed > 0">
              ，其中 {{ unattributed }} 条后端返回 NONE（未归因），单独列出以便对账
            </template>
            。列表按归因层排序：编译 → 召回 → 排序 → 生成 → 兜底 → 未归因。
          </p>

          <FailureList
            :run-id="run.runId"
            :rows="visible"
            :total="matchedTotal"
            :layer-counts="layerCounts"
            :layer="layer"
            :keyword="keyword"
            :limit="null"
            @update:layer="setLayer"
            @update:keyword="setKeyword"
            @jump="backToReport"
          />
        </template>
      </div>
    </div>
  </AppLayout>
</template>

<style scoped>
.cases-scroll {
  overflow: auto;
  padding: 20px clamp(18px, 2.6vw, 32px) 36px;
  scrollbar-width: thin;
  background: var(--paper-raised);
  min-height: 0;
}

.cases-inner {
  max-width: 1120px;
  margin: 0 auto;
}

.cases-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.head-main {
  min-width: 0;
}

.cases-title {
  font-family: var(--serif);
  font-size: 21px;
  font-weight: 700;
  margin: 0;
  line-height: 1.35;
}

.cases-sub {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
  font-size: 11px;
  color: var(--ink-mute);
  margin-top: 4px;
}

.cases-sub .strong {
  color: var(--ink-soft);
}

.cases-sub .warn {
  color: var(--amber);
}

.cases-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  flex: none;
}

.cases-note {
  margin: 0 0 12px;
  padding: 7px 10px;
  font-size: 10.5px;
  line-height: 1.7;
  color: var(--ink-mute);
  background: var(--paper);
  border: 1px solid var(--line-soft);
  border-radius: var(--r-s);
}

.loading-stack {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

@media (max-width: 760px) {
  .cases-scroll {
    padding: 14px 11px 24px;
  }

  .cases-head {
    flex-direction: column;
    gap: 9px;
  }

  .cases-title {
    font-size: 18px;
  }

  .cases-sub {
    font-size: 10px;
    gap: 5px;
  }

  .cases-actions {
    width: 100%;
  }

  .cases-actions .btn {
    flex: 1;
  }
}
</style>
