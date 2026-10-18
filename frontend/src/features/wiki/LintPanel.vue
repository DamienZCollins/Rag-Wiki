<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import type { LintIssue, LintKind, LintReport } from '@/api/wiki'

/**
 * lint 结果面板。后端四类：矛盾 / 过期 / 孤页 / 待创建，
 * 原型只有三类，这里按后端实际返回补齐第四类。
 */

const props = defineProps<{
  report: LintReport
  /** 当前条目，用于高亮涉及本条的 issue */
  currentId?: string
}>()

interface KindMeta {
  kind: LintKind
  label: string
  hint: string
}

const KIND_ORDER: KindMeta[] = [
  { kind: 'CONFLICT', label: '矛盾', hint: '同一概念在不同条目里给出冲突结论' },
  { kind: 'EXPIRED', label: '过期', hint: '生效期或有效期与状态不一致' },
  { kind: 'ORPHAN', label: '孤页', hint: '没有任何入链或出链' },
  { kind: 'PENDING_LINK', label: '待创建', hint: '双链指向的条目还不存在' },
]

const severityLabel: Record<string, string> = {
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
}

const severityTone: Record<string, string> = {
  HIGH: 'high',
  MEDIUM: 'med',
  LOW: 'low',
}

const groups = computed(() =>
  KIND_ORDER.map((meta) => ({
    ...meta,
    issues: props.report[issuesKeyOf(meta.kind)],
  })).filter((group) => group.issues.length > 0),
)

const totalIssues = computed(
  () =>
    props.report.conflicts.length +
    props.report.expired.length +
    props.report.orphans.length +
    props.report.pendingLinks.length,
)

function issuesKeyOf(kind: LintKind): 'conflicts' | 'expired' | 'orphans' | 'pendingLinks' {
  switch (kind) {
    case 'CONFLICT':
      return 'conflicts'
    case 'EXPIRED':
      return 'expired'
    case 'ORPHAN':
      return 'orphans'
    default:
      return 'pendingLinks'
  }
}

function isCurrent(issue: LintIssue): boolean {
  return Boolean(props.currentId) && issue.pageId === props.currentId
}
</script>

<template>
  <div class="lint-panel">
    <div class="lint-summary">
      <span>共</span>
      <b class="lint-summary-total">{{ totalIssues }}</b>
      <span>条问题</span>
      <span class="dot-sep" />
      <span>检查 {{ report.pageCount }} 个条目</span>
    </div>

    <p v-if="totalIssues === 0" class="lint-clean">
      四类检查全部通过：没有矛盾、过期、孤页或待创建双链。
    </p>

    <section v-for="group in groups" :key="group.kind" class="lint-group">
      <header class="lint-group-head">
        <span class="lint-group-title">{{ group.label }}</span>
        <span class="tag" :class="group.kind === 'CONFLICT' ? 'ox' : 'amber'">
          {{ group.issues.length }} 条
        </span>
      </header>
      <p class="lint-group-hint">{{ group.hint }}</p>

      <RouterLink
        v-for="issue in group.issues"
        :key="`${issue.kind}-${issue.pageId}-${issue.relatedPageId ?? issue.detail}`"
        class="lint-item"
        :class="{ 'lint-item--current': isCurrent(issue) }"
        :to="{ name: 'wiki-page', params: { pageId: issue.pageId } }"
        :aria-current="isCurrent(issue) ? 'page' : undefined"
      >
        <span class="sev" :class="severityTone[issue.severity]" :aria-hidden="true" />
        <span class="lint-item-body">
          <span class="lint-item-title">
            <b>{{ issue.title }}</b>
            <span class="tag" :class="issue.severity === 'HIGH' ? 'ox' : 'plain'">
              严重度 {{ severityLabel[issue.severity] ?? issue.severity }}
            </span>
          </span>
          <span class="lint-item-page mono">{{ issue.pageId }}</span>
          <span class="lint-item-detail">{{ issue.detail }}</span>
          <span v-if="issue.relatedTitle" class="lint-item-related">
            冲突另一方：
            <RouterLink
              v-if="issue.relatedPageId"
              class="lint-related-link"
              :to="{ name: 'wiki-page', params: { pageId: issue.relatedPageId } }"
              @click.stop
            >
              {{ issue.relatedTitle }}
            </RouterLink>
            <template v-else>{{ issue.relatedTitle }}</template>
          </span>
        </span>
      </RouterLink>
    </section>
  </div>
</template>

<style scoped>
.lint-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.lint-summary {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11.5px;
  color: var(--ink-mute);
}

.lint-summary-total {
  color: var(--ink);
  font-size: 13px;
}

.lint-clean {
  margin: 0;
  padding: 14px;
  border: 1px solid var(--green-line);
  background: var(--green-soft);
  border-radius: var(--r-m);
  font-size: 12px;
  line-height: 1.7;
  color: var(--green);
}

.lint-group {
  border: 1px solid var(--line);
  border-radius: var(--r-m);
  overflow: hidden;
  background: var(--paper-raised);
}

.lint-group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 9px 12px;
  border-bottom: 1px solid var(--line-soft);
}

.lint-group-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--ink);
}

.lint-group-hint {
  margin: 0;
  padding: 7px 12px 0;
  font-size: 10.5px;
  color: var(--ink-faint);
}

.lint-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 9px 12px;
  border-bottom: 1px solid var(--line-soft);
  text-decoration: none;
}

.lint-item:last-child {
  border-bottom: 0;
}

.lint-item:hover {
  background: var(--paper);
}

.lint-item--current {
  background: var(--paper);
  box-shadow: inset 2px 0 0 var(--oxblood);
}

.sev {
  width: 4px;
  border-radius: 3px;
  align-self: stretch;
  background: var(--line-strong);
  flex: none;
}

.sev.high {
  background: var(--oxblood);
}

.sev.med {
  background: var(--amber);
}

.sev.low {
  background: var(--blue);
}

.lint-item-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.lint-item-title {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.lint-item-title b {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-soft);
  line-height: 1.5;
}

.lint-item:hover .lint-item-title b {
  color: var(--oxblood);
}

.lint-item-page {
  font-size: 10px;
  color: var(--ink-faint);
  word-break: break-all;
}

.lint-item-detail {
  font-size: 11px;
  line-height: 1.7;
  color: var(--ink-mute);
}

.lint-item-related {
  font-size: 11px;
  line-height: 1.7;
  color: var(--ink-mute);
}

.lint-related-link {
  color: var(--teal);
  text-decoration: none;
  border-bottom: 1px solid var(--teal-line);
}

.lint-related-link:hover {
  border-bottom-color: var(--teal);
}
</style>
