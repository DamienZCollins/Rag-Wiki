<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import { rebuildIndex, type RebuildReport } from '@/api/rag'
import { getIndexStatus, switchIndex, type IndexStatus } from '@/api/eval'
import { useToast } from '@/composables/useToast'
import OpsTask from './OpsTask.vue'
import OpsResultMeta from './OpsResultMeta.vue'
import { formatDuration, formatInt } from './format'
import { useOpsAction } from './useOpsAction'
import { useOpsConfirm } from './useOpsConfirm'
import type { MetaItem } from './types'

/**
 * RAG 索引：重建、别名状态查询、别名切换。
 * 重建与切换都改线上检索行为，必须二次确认；状态查询是只读的。
 */
const toast = useToast()
const confirm = useOpsConfirm()

const statusAction = useOpsAction<IndexStatus>()
const rebuildAction = useOpsAction<RebuildReport>()
const switchAction = useOpsAction<IndexStatus>()

const selectedIndex = ref('')

const knownIndices = computed<string[]>(() => {
  // 物理索引以接口返回为准，切换成功/状态查询后都刷新
  const latest =
    switchAction.outcome.value?.data.physicalIndices ??
    statusAction.outcome.value?.data.physicalIndices ??
    []
  return latest
})

const currentTarget = computed<string | null>(() => {
  return (
    switchAction.outcome.value?.data.aliasTarget ??
    statusAction.outcome.value?.data.aliasTarget ??
    null
  )
})

const candidateIndices = computed(() => {
  const indices = knownIndices.value
  if (indices.length > 0) return indices
  return []
})

const canSwitch = computed(
  () =>
    selectedIndex.value.length > 0 &&
    selectedIndex.value !== currentTarget.value &&
    switchAction.phase.value !== 'running',
)

onMounted(() => {
  void refreshStatus()
})

async function refreshStatus() {
  const status = await statusAction.run((signal) => getIndexStatus(signal))
  if (status) {
    if (!selectedIndex.value) {
      selectedIndex.value = status.aliasTarget ?? status.physicalIndices[0] ?? ''
    }
    toast.info(`索引状态已刷新：别名指向 ${status.aliasTarget ?? '未绑定'}`)
  }
}

async function onRebuild() {
  if (rebuildAction.phase.value === 'running') return
  const accepted = await confirm.ask({
    title: '确认重建 RAG 索引？',
    message: '删除当前物理索引，重新切分、逐条向量化并写入。',
    impact:
      '影响范围：重建期间在线检索可能查不到内容或命中旧数据；索引源由 rag.index.source 决定（doc 读 corpus/policies，wiki 读 wiki/pages）。预计耗时：几分钟（每条片段都要调 embedding）。',
    confirmText: '开始重建',
    tone: 'danger',
  })
  if (!accepted) return
  const report = await rebuildAction.run((signal) => rebuildIndex(signal))
  if (report) {
    toast.success(
      `索引重建完成：${formatInt(report.documents)} 篇文档，${formatInt(report.chunks)} 个片段`,
    )
    void refreshStatus()
  }
}

async function onSwitch() {
  if (switchAction.phase.value === 'running') return
  const target = selectedIndex.value
  if (!target) {
    toast.error('先查询索引状态，或填一个物理索引名')
    return
  }
  if (target === currentTarget.value) {
    toast.info(`别名已经指向 ${target}，无需切换`)
    return
  }
  const accepted = await confirm.ask({
    title: '确认切换索引别名？',
    message: `把别名 knowledge_chunks 从 ${currentTarget.value ?? '（未绑定）'} 切到 ${target}。`,
    impact:
      '影响范围：立即改变在线检索与评测探针实际命中的索引，正在进行的联调与对比实验会跟着改变。预计耗时：秒级，但影响持续到下次切回。',
    confirmText: '确认切换',
    tone: 'danger',
  })
  if (!accepted) return
  const status = await switchAction.run((signal) => switchIndex(target, signal))
  if (status) {
    toast.success(`索引别名已指向 ${status.aliasTarget ?? target}`)
    void refreshStatus()
  }
}

const statusMeta = computed<MetaItem[]>(() => {
  const status = statusAction.outcome.value?.data
  if (!status) return []
  return [
    {
      label: '别名指向',
      value: status.aliasTarget ?? '未绑定',
      tone: status.aliasTarget ? 'ok' : 'warn',
      wide: true,
    },
    { label: '物理索引数', value: formatInt(status.physicalIndices.length) },
  ]
})

const rebuildMeta = computed<MetaItem[]>(() => {
  const report = rebuildAction.outcome.value?.data
  if (!report) return []
  return [
    { label: '写入索引', value: report.index, mono: true, wide: true },
    { label: '文档数', value: formatInt(report.documents) },
    { label: '片段数', value: formatInt(report.chunks) },
    { label: '后端耗时', value: formatDuration(report.elapsedMillis) },
  ]
})

const switchMeta = computed<MetaItem[]>(() => {
  const status = switchAction.outcome.value?.data
  if (!status) return []
  return [
    { label: '切换后指向', value: status.aliasTarget ?? '未绑定', tone: 'ok', wide: true },
    { label: '物理索引数', value: formatInt(status.physicalIndices.length) },
  ]
})

const aliasLists = computed(() => {
  const status = statusAction.outcome.value?.data ?? switchAction.outcome.value?.data
  if (!status) return []
  return [{ label: '物理索引', values: status.physicalIndices }]
})
</script>

<template>
  <div class="task-grid">
    <OpsTask
      title="索引状态查询"
      description="查看别名当前指向哪个物理索引，以及集群里现存的物理索引清单。只读接口。"
      :running="statusAction.phase.value === 'running'"
      :elapsed-ms="statusAction.elapsedMs.value"
      :failure="statusAction.failure.value"
      :outcome="statusAction.outcome.value"
      result-title="状态返回"
      idle-text="尚未查询索引状态"
      @retry="refreshStatus"
    >
      <template #actions>
        <button
          class="btn sm"
          type="button"
          :disabled="statusAction.phase.value === 'running'"
          @click="refreshStatus"
        >
          <AppIcon name="search" :size="14" />
          {{ statusAction.phase.value === 'running' ? '查询中…' : '查询索引状态' }}
        </button>
      </template>

      <template #result>
        <OpsResultMeta :items="statusMeta" :lists="aliasLists" />
      </template>
    </OpsTask>

    <OpsTask
      title="索引重建"
      description="删掉物理索引重新切分、向量化、写入，可重复执行。索引源由后端配置决定。"
      impact="重建期间在线检索可能查不到内容或命中旧数据。"
      cost="几分钟（每条片段都要重新向量化）"
      dangerous
      :running="rebuildAction.phase.value === 'running'"
      :elapsed-ms="rebuildAction.elapsedMs.value"
      :failure="rebuildAction.failure.value"
      :outcome="rebuildAction.outcome.value"
      result-title="重建返回"
      idle-text="尚未执行重建"
      @retry="onRebuild"
    >
      <template #actions>
        <button
          class="btn ox sm"
          type="button"
          :disabled="rebuildAction.phase.value === 'running'"
          @click="onRebuild"
        >
          <AppIcon name="layers" :size="14" />
          {{ rebuildAction.phase.value === 'running' ? '重建中…' : '重建索引' }}
        </button>
      </template>

      <template #result>
        <OpsResultMeta :items="rebuildMeta" />
      </template>
    </OpsTask>

    <OpsTask
      title="索引别名切换"
      description="把查询别名切到另一个物理索引。索引源对照实验用：探针与在线问答都走别名。"
      impact="立即改变在线检索实际命中的索引，会影响正在进行的联调与评测对比。"
      cost="秒级返回，但影响持续到下次切回"
      dangerous
      :running="switchAction.phase.value === 'running'"
      :elapsed-ms="switchAction.elapsedMs.value"
      :failure="switchAction.failure.value"
      :outcome="switchAction.outcome.value"
      result-title="切换返回"
      idle-text="尚未执行别名切换"
      @retry="onSwitch"
    >
      <template #controls>
        <label class="ops-field">
          <span>目标物理索引</span>
          <select
            v-if="candidateIndices.length > 0"
            v-model="selectedIndex"
            class="ops-input"
            :disabled="switchAction.phase.value === 'running'"
          >
            <option v-for="index in candidateIndices" :key="index" :value="index">
              {{ index }}{{ index === currentTarget ? '（当前指向）' : '' }}
            </option>
          </select>
          <input
            v-else
            v-model="selectedIndex"
            class="ops-input mono"
            type="text"
            placeholder="先查询索引状态，或手填物理索引名"
            :disabled="switchAction.phase.value === 'running'"
          />
        </label>
        <p class="ops-hint">
          当前别名指向
          <span class="mono">{{ currentTarget ?? '未知（先查询索引状态）' }}</span>
        </p>
      </template>

      <template #actions>
        <button
          class="btn ox sm"
          type="button"
          :disabled="!canSwitch"
          :aria-disabled="!canSwitch"
          @click="onSwitch"
        >
          <AppIcon name="external" :size="14" />
          {{ switchAction.phase.value === 'running' ? '切换中…' : '切换别名' }}
        </button>
        <span v-if="!currentTarget" class="ops-hint">切换前需要先查到当前指向</span>
        <span v-else-if="selectedIndex === currentTarget" class="ops-hint"> 别名已指向该索引 </span>
      </template>

      <template #result>
        <OpsResultMeta :items="switchMeta" :lists="aliasLists" />
      </template>
    </OpsTask>

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
.task-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}

.ops-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1 1 240px;
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

@media (max-width: 1080px) {
  .task-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
