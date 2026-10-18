<script setup lang="ts">
import { computed, useId } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import OpsFailureNotice from './OpsFailureNotice.vue'
import { formatClock, formatDuration } from './format'
import type { OpsFailure, OpsOutcome } from './types'

/**
 * 单个运维操作的卡片。四态齐全：未执行 / 等待后端返回 / 成功 / 失败。
 *
 * 这些接口是同步的，后端没返回就是还没跑完，因此等待态只展示已等待时长，
 * 不画进度条、不估算剩余时间。
 */
const props = withDefaults(
  defineProps<{
    title: string
    description?: string
    /** 影响范围：改动了什么 */
    impact?: string
    /** 预计耗时，写的是实测或接口特性决定的数量级 */
    cost?: string
    /** 危险操作，影响范围用告警色 */
    dangerous?: boolean
    running: boolean
    elapsedMs: number
    failure: OpsFailure | null
    /** 有成功返回时传入，用于渲染结果区 */
    outcome?: OpsOutcome<unknown> | null
    resultTitle?: string
    /** 结果区为空时的说明 */
    idleText?: string
    retryText?: string
  }>(),
  { resultTitle: '最近一次返回', idleText: '尚未执行', retryText: '重试' },
)

const emit = defineEmits<{ retry: [] }>()

const headingId = `ops-task-${useId()}`
const waited = computed(() => formatDuration(props.elapsedMs))
const hasOutcome = computed(() => props.outcome !== undefined && props.outcome !== null)
</script>

<template>
  <section
    class="ops-task"
    data-ops-task
    :data-ops-task-title="title"
    :class="{ 'is-running': running }"
    :aria-busy="running"
    :aria-labelledby="headingId"
  >
    <header class="ops-task__head">
      <div class="ops-task__heading">
        <h3 :id="headingId" class="ops-task__title">{{ title }}</h3>
        <p v-if="description" class="ops-task__desc">{{ description }}</p>
      </div>
      <span v-if="running" class="tag amber ops-task__state">执行中</span>
    </header>

    <dl v-if="impact || cost" class="ops-task__scope">
      <div v-if="impact">
        <dt>影响范围</dt>
        <dd :class="{ 'is-danger': dangerous }">{{ impact }}</dd>
      </div>
      <div v-if="cost">
        <dt>预计耗时</dt>
        <dd>{{ cost }}</dd>
      </div>
    </dl>

    <div v-if="$slots.controls" class="ops-task__controls">
      <slot name="controls" />
    </div>

    <div class="ops-task__actions">
      <slot name="actions" />
    </div>

    <div v-if="running" class="ops-waiting" role="status" aria-live="polite">
      <AppIcon name="refresh" :size="14" class="ops-waiting__icon" />
      <div class="ops-waiting__body">
        <b>等待后端返回</b>
        <span>
          同步接口，已等待<span class="mono"> {{ waited }}</span>
          ，返回前不会有中间进度，请不要关闭页面。
        </span>
      </div>
    </div>

    <OpsFailureNotice
      v-else-if="failure"
      :failure="failure"
      :title="`${title}失败`"
      @retry="emit('retry')"
    />

    <div v-else-if="hasOutcome && outcome" class="ops-result">
      <div class="ops-result__head">
        <span class="ops-result__label">{{ resultTitle }}</span>
        <span class="ops-result__meta mono">
          {{ formatClock(outcome.finishedAt) }} 返回 · 页面等待
          {{ formatDuration(outcome.elapsedMs) }}
        </span>
      </div>
      <slot name="result" :data="outcome.data" />
    </div>

    <p v-else class="ops-task__idle">{{ idleText }}</p>
  </section>
</template>

<style scoped>
.ops-task {
  border: 1px solid var(--line);
  border-radius: var(--r-m);
  background: var(--paper-raised);
  padding: 13px 14px 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}

.ops-task.is-running {
  border-color: var(--amber-line);
}

.ops-task__head {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.ops-task__heading {
  min-width: 0;
  flex: 1;
}

.ops-task__title {
  margin: 0;
  font-family: var(--serif);
  font-size: 14px;
  font-weight: 700;
  line-height: 1.4;
}

.ops-task__desc {
  margin: 3px 0 0;
  font-size: 11.5px;
  line-height: 1.65;
  color: var(--ink-mute);
}

.ops-task__state {
  flex: none;
  margin-top: 1px;
}

.ops-task__scope {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 8px 10px;
  background: var(--paper);
  border: 1px solid var(--line-soft);
  border-radius: var(--r-s);
  font-size: 11px;
}

.ops-task__scope > div {
  display: flex;
  gap: 8px;
  align-items: baseline;
}

.ops-task__scope dt {
  flex: none;
  color: var(--ink-faint);
  min-width: 52px;
}

.ops-task__scope dd {
  margin: 0;
  color: var(--ink-soft);
  line-height: 1.65;
}

.ops-task__scope dd.is-danger {
  color: var(--oxblood);
}

.ops-task__controls {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: flex-end;
}

.ops-task__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  align-items: center;
}

.ops-waiting {
  display: flex;
  gap: 9px;
  align-items: flex-start;
  padding: 9px 11px;
  border: 1px solid var(--amber-line);
  background: var(--amber-soft);
  border-radius: var(--r-s);
}

.ops-waiting__icon {
  color: var(--amber);
  margin-top: 2px;
  flex: none;
  animation: opsSpin 1.6s linear infinite;
}

.ops-waiting__body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  font-size: 11.5px;
  color: var(--ink-soft);
  line-height: 1.65;
}

.ops-waiting__body b {
  color: var(--amber);
  font-size: 12px;
}

.ops-result {
  border-top: 1px dashed var(--line);
  padding-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.ops-result__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.ops-result__label {
  font-size: 11px;
  font-weight: 700;
  color: var(--ink-soft);
}

.ops-result__meta {
  font-size: 10.5px;
  color: var(--ink-faint);
}

.ops-task__idle {
  margin: 0;
  font-size: 11px;
  color: var(--ink-faint);
  border-top: 1px dashed var(--line-soft);
  padding-top: 9px;
}

@keyframes opsSpin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .ops-waiting__icon {
    animation: none;
  }
}
</style>
