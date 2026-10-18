<script setup lang="ts">
import { computed, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import { compile, runLint, type CompileReport, type LintReport } from '@/api/wiki'
import { useToast } from '@/composables/useToast'
import OpsTask from './OpsTask.vue'
import OpsResultMeta from './OpsResultMeta.vue'
import { formatDuration, formatInt } from './format'
import { useOpsAction } from './useOpsAction'
import { useOpsConfirm } from './useOpsConfirm'
import type { MetaItem } from './types'

/**
 * Wiki 编译与条目体检。
 * 编译三选一：全量 / 增量 / 单篇，都走 POST /api/wiki/compile；
 * 单篇靠 sourceDocId 传参，其余模式传 incremental。
 */
type CompileMode = 'full' | 'incremental' | 'single'

const MODE_LABELS: Record<CompileMode, string> = {
  full: '全量',
  incremental: '增量',
  single: '单篇',
}

const toast = useToast()
const confirm = useOpsConfirm()

const mode = ref<CompileMode>('incremental')
const sourceDocId = ref('')

const sourceDocError = computed(() =>
  mode.value === 'single' && sourceDocId.value.trim().length === 0
    ? '单篇编译必须填源文档标识'
    : '',
)

const {
  phase: compilePhase,
  elapsedMs: compileElapsed,
  outcome: compileOutcome,
  failure: compileFailure,
  run: runCompile,
  reset: resetCompile,
} = useOpsAction<CompileReport>()

const {
  phase: lintPhase,
  elapsedMs: lintElapsed,
  outcome: lintOutcome,
  failure: lintFailure,
  run: runLintAction,
} = useOpsAction<LintReport>()

const compileConfirm = computed(() => {
  if (mode.value === 'single') {
    return {
      title: '确认执行单篇编译？',
      message: `只编译源文档 ${sourceDocId.value.trim()}，允许编译已失效文档，用于复现历史状态。`,
      impact:
        '影响范围：重写该文档对应的 Wiki 条目并落盘，清空快通道场景缓存；答案正文要重新派生并发布才会更新。预计耗时：数十秒到数分钟。',
      confirmText: '开始编译',
    }
  }
  if (mode.value === 'incremental') {
    return {
      title: '确认执行增量编译？',
      message: '只重算输入内容有变化的源文档，并经双链传播带出受影响的条目。',
      impact:
        '影响范围：改写受影响的 Wiki 条目、把被替代的旧条目转失效，清空快通道场景缓存；不会自动 git 提交。预计耗时：数十秒到数分钟（有模型调用）。',
      confirmText: '开始编译',
    }
  }
  return {
    title: '确认执行全量编译？',
    message: '忽略已有产物，把全部生效源文档重新编译一遍。',
    impact:
      '影响范围：改写全部 Wiki 条目、把被替代的旧条目转失效，清空快通道场景缓存；不会自动 git 提交。预计耗时：最久，十几分钟量级。',
    confirmText: '开始全量编译',
    tone: 'danger' as const,
  }
})

async function onCompile() {
  if (compilePhase.value === 'running') return
  if (sourceDocError.value) {
    toast.error(sourceDocError.value)
    return
  }
  const accepted = await confirm.ask(compileConfirm.value)
  if (!accepted) return
  const payload =
    mode.value === 'single'
      ? { sourceDocId: sourceDocId.value.trim() }
      : { incremental: mode.value === 'incremental' }
  const report = await runCompile((signal) => compile(payload, signal))
  if (report) {
    toast.success(
      `编译完成：新建 ${report.created} 条，更新 ${report.updated} 条，失效 ${report.retired} 条`,
    )
  }
}

const compileMeta = computed<MetaItem[]>(() => {
  const report = compileOutcome.value?.data
  if (!report) return []
  return [
    { label: '编译文档', value: formatInt(report.compiledDocs.length) },
    { label: '跳过文档', value: formatInt(report.skippedDocs.length) },
    { label: '新建条目', value: formatInt(report.created), tone: 'ok' },
    { label: '更新条目', value: formatInt(report.updated) },
    {
      label: '失效条目',
      value: formatInt(report.retired),
      tone: report.retired > 0 ? 'warn' : undefined,
    },
    {
      label: '失败文档',
      value: formatInt(report.failedDocs.length),
      tone: report.failedDocs.length > 0 ? 'bad' : undefined,
    },
    { label: '模型调用', value: formatInt(report.modelCalls) },
    { label: '后端耗时', value: formatDuration(report.elapsedMillis) },
    { label: 'prompt token', value: formatInt(report.promptTokens) },
    { label: 'completion token', value: formatInt(report.completionTokens) },
  ]
})

const compileLists = computed(() => {
  const report = compileOutcome.value?.data
  if (!report) return []
  return [
    { label: '新建条目', values: report.createdPages },
    { label: '更新条目', values: report.updatedPages },
    { label: '转失效条目', values: report.retiredPages },
    { label: '失败文档', values: report.failedDocs },
    { label: '跳过文档（输入无变化）', values: report.skippedDocs },
    { label: '编译说明', values: report.notes },
  ]
})

const lintMeta = computed<MetaItem[]>(() => {
  const report = lintOutcome.value?.data
  if (!report) return []
  const total =
    report.conflicts.length +
    report.expired.length +
    report.orphans.length +
    report.pendingLinks.length
  return [
    { label: '条目总数', value: formatInt(report.pageCount) },
    { label: '问题合计', value: formatInt(total), tone: total > 0 ? 'warn' : 'ok' },
    {
      label: '口径矛盾',
      value: formatInt(report.conflicts.length),
      tone: report.conflicts.length > 0 ? 'bad' : undefined,
    },
    {
      label: '已过期',
      value: formatInt(report.expired.length),
      tone: report.expired.length > 0 ? 'warn' : undefined,
    },
    {
      label: '孤页',
      value: formatInt(report.orphans.length),
      tone: report.orphans.length > 0 ? 'warn' : undefined,
    },
    {
      label: '待创建双链',
      value: formatInt(report.pendingLinks.length),
      tone: report.pendingLinks.length > 0 ? 'warn' : undefined,
    },
  ]
})

const lintGroups = computed(() => {
  const report = lintOutcome.value?.data
  if (!report) return []
  return [
    { key: 'CONFLICT', label: '口径矛盾', tone: 'ox', issues: report.conflicts },
    { key: 'EXPIRED', label: '已过期', tone: 'amber', issues: report.expired },
    { key: 'ORPHAN', label: '孤页', tone: 'amber', issues: report.orphans },
    { key: 'PENDING_LINK', label: '待创建双链', tone: 'amber', issues: report.pendingLinks },
  ].filter((group) => group.issues.length > 0)
})

const lintIssueCount = computed(() => {
  const report = lintOutcome.value?.data
  if (!report) return 0
  return (
    report.conflicts.length +
    report.expired.length +
    report.orphans.length +
    report.pendingLinks.length
  )
})

async function onLint() {
  if (lintPhase.value === 'running') return
  const report = await runLintAction((signal) => runLint(signal))
  if (report) {
    toast.success(`体检完成：${report.pageCount} 个条目，${lintIssueCount.value} 条问题`)
  }
}
</script>

<template>
  <div class="task-grid">
    <OpsTask
      title="Wiki 编译"
      description="把源文档编译成互链的结构化条目：全量重算、增量只重算输入变化的文档，单篇用于复现历史状态。"
      impact="改写 wiki/pages 下的条目产物并清空快通道场景缓存；不做自动 git 提交。"
      cost="增量：数十秒到数分钟；全量：十几分钟量级"
      :running="compilePhase === 'running'"
      :elapsed-ms="compileElapsed"
      :failure="compileFailure"
      :outcome="compileOutcome"
      result-title="编译返回"
      idle-text="尚未执行编译"
      @retry="onCompile"
    >
      <template #controls>
        <div class="compile-controls">
          <div class="seg" role="tablist" aria-label="编译模式">
            <button
              v-for="option in ['incremental', 'full', 'single'] as CompileMode[]"
              :key="option"
              type="button"
              role="tab"
              :aria-selected="mode === option"
              :disabled="compilePhase === 'running'"
              @click="mode = option"
            >
              {{ MODE_LABELS[option] }}
            </button>
          </div>
          <label v-if="mode === 'single'" class="ops-field">
            <span>源文档标识</span>
            <input
              v-model="sourceDocId"
              class="ops-input mono"
              type="text"
              placeholder="例如 policy-freight-rule-v2024"
              :disabled="compilePhase === 'running'"
              @keyup.enter="onCompile"
            />
          </label>
          <p class="ops-hint">
            {{
              mode === 'single'
                ? '单篇编译允许编译已失效文档；sourceDocId 取自 corpus 目录下的文档标识。'
                : mode === 'incremental'
                  ? '增量编译经双链传播带出受影响的条目。'
                  : '全量编译忽略已有产物，耗时最长。'
            }}
          </p>
        </div>
      </template>

      <template #actions>
        <button
          class="btn primary sm"
          type="button"
          :disabled="compilePhase === 'running' || Boolean(sourceDocError)"
          @click="onCompile"
        >
          <AppIcon name="refresh" :size="14" />
          {{ compilePhase === 'running' ? '编译中…' : `${MODE_LABELS[mode]}编译` }}
        </button>
        <button
          v-if="compileOutcome || compileFailure"
          class="btn ghost sm"
          type="button"
          :disabled="compilePhase === 'running'"
          @click="resetCompile()"
        >
          清空结果
        </button>
      </template>

      <template #result="{ data }">
        <OpsResultMeta :items="compileMeta" :lists="compileLists" />
        <p v-if="(data as CompileReport).failedDocs.length > 0" class="ops-warn-line">
          <AppIcon name="warning" :size="13" />
          有 {{ (data as CompileReport).failedDocs.length }} 篇文档编译失败，清单见「失败文档」。
        </p>
      </template>
    </OpsTask>

    <OpsTask
      title="条目体检"
      description="检测口径矛盾、过期条目、孤页与待创建双链，结果按类型分组，可定位到具体条目。"
      impact="只读检测，不改动条目；会调用模型做矛盾判定。"
      cost="十几秒到几十秒"
      :running="lintPhase === 'running'"
      :elapsed-ms="lintElapsed"
      :failure="lintFailure"
      :outcome="lintOutcome"
      result-title="体检返回"
      idle-text="尚未执行体检"
      @retry="onLint"
    >
      <template #actions>
        <button class="btn sm" type="button" :disabled="lintPhase === 'running'" @click="onLint">
          <AppIcon name="check" :size="14" />
          {{ lintPhase === 'running' ? '体检中…' : '开始体检' }}
        </button>
        <RouterLink class="btn ghost sm" to="/wiki/lint">到条目体检页</RouterLink>
      </template>

      <template #result>
        <OpsResultMeta :items="lintMeta" />
        <ul v-if="lintGroups.length > 0" class="lint-groups">
          <li v-for="group in lintGroups" :key="group.key" class="lint-group">
            <div class="lint-group__head">
              <span class="tag" :class="group.tone">{{ group.label }}</span>
              <span class="mono">{{ group.issues.length }}</span>
            </div>
            <ul class="lint-list">
              <li
                v-for="issue in group.issues"
                :key="`${issue.kind}-${issue.pageId}-${issue.relatedPageId ?? ''}`"
              >
                <span class="sev" :class="issue.severity.toLowerCase()">{{ issue.severity }}</span>
                <span class="lint-list__title">{{ issue.title }}</span>
                <span class="lint-list__detail">{{ issue.detail }}</span>
                <span v-if="issue.relatedTitle" class="lint-list__rel">
                  相关：{{ issue.relatedTitle }}
                </span>
              </li>
            </ul>
          </li>
        </ul>
        <p v-else class="ops-ok-line">
          <AppIcon name="check" :size="13" />
          本次体检未发现问题：{{ lintOutcome?.data.pageCount }}
          个条目在口径矛盾、过期、孤页、待创建双链四类检查上均无告警。
        </p>
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

.compile-controls {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1 1 240px;
  min-width: 0;
}

.compile-controls .seg {
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

.ops-input:focus-visible {
  border-color: var(--oxblood);
}

.ops-hint {
  margin: 0;
  font-size: 10.5px;
  line-height: 1.6;
  color: var(--ink-faint);
}

.ops-warn-line,
.ops-ok-line {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 0;
  font-size: 11px;
}

.ops-warn-line {
  color: var(--amber);
}

.ops-ok-line {
  color: var(--green);
}

.lint-groups {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.lint-group {
  border: 1px solid var(--line-soft);
  border-radius: var(--r-s);
  background: var(--paper);
  padding: 8px 10px;
}

.lint-group__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 10.5px;
  color: var(--ink-faint);
}

.lint-list {
  margin: 7px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 220px;
  overflow: auto;
}

.lint-list li {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 5px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-soft);
}

.sev {
  flex: none;
  font-family: var(--mono);
  font-size: 9.5px;
  padding: 0 4px;
  border-radius: 3px;
  border: 1px solid var(--line);
  color: var(--ink-mute);
}

.sev.high {
  color: var(--oxblood);
  border-color: var(--oxblood-line);
  background: var(--oxblood-soft);
}

.sev.medium {
  color: var(--amber);
  border-color: var(--amber-line);
  background: var(--amber-soft);
}

.lint-list__title {
  font-weight: 600;
  color: var(--ink);
}

.lint-list__detail {
  color: var(--ink-mute);
}

.lint-list__rel {
  font-size: 10px;
  color: var(--ink-faint);
}

@media (max-width: 1080px) {
  .task-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
