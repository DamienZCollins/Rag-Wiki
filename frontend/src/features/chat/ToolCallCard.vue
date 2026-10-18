<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import type { ToolTrace } from './types'

/**
 * 工具调用折叠卡片。
 * 默认收起，收起时只占一行；按 callId 与 TOOL_RESULT 配对，同一工具多次调用各自独立。
 * 工具失败只在卡片内展示，不冒泡成整轮失败。
 */

const props = defineProps<{ trace: ToolTrace }>()

const TOOL_LABELS: Record<string, string> = {
  search_knowledge: '检索知识库',
  query_order: '查订单',
  create_ticket: '生成工单',
  refund: '发起退款',
}

const label = computed(() => TOOL_LABELS[props.trace.name] ?? props.trace.name)

const statusText = computed(() => {
  switch (props.trace.status) {
    case 'running':
      return '执行中'
    case 'success':
      return '成功'
    default:
      return '失败'
  }
})

const tone = computed(() => {
  switch (props.trace.status) {
    case 'running':
      return 'amber'
    case 'success':
      return 'green'
    default:
      return 'ox'
  }
})

/** 事件没带耗时，按 TOOL_CALL 与 TOOL_RESULT 的到达时间估算 */
const elapsed = computed(() => {
  if (props.trace.endedAt === null) return null
  const ms = props.trace.endedAt - props.trace.startedAt
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(2)} s`
})

const argsText = computed(() => {
  const raw = props.trace.arguments?.trim()
  if (!raw) return '（无入参）'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
})

const resultText = computed(() => props.trace.content ?? '')
const missPayload = computed(() => {
  const text = resultText.value.trim()
  return text.includes('"hit":false') || text.includes('"hit": false')
})
</script>

<template>
  <details class="tool-trace" :class="`tool-trace--${trace.status}`">
    <summary>
      <AppIcon name="chev" :size="13" class="chev" />
      <span class="tool-name">{{ label }}</span>
      <span class="tool-raw mono">{{ trace.name }}</span>
      <span v-if="elapsed" class="mono tool-elapsed">{{ elapsed }}</span>
      <span class="tag" :class="tone">{{ statusText }}</span>
    </summary>
    <div class="steps">
      <div class="step-block">
        <div class="step-title">入参</div>
        <pre class="code">{{ argsText }}</pre>
      </div>
      <div class="step-block">
        <div class="step-title">返回</div>
        <div v-if="trace.status === 'running'" class="pending">等待工具返回…</div>
        <pre v-else class="code" :class="{ 'code--error': trace.status === 'error' }">{{ resultText || '（空返回）' }}</pre>
        <p v-if="trace.status === 'error'" class="tool-error-note">
          工具执行失败，本轮对话继续：失败信息已回传给模型，由模型决定如何向用户解释。
        </p>
        <p v-else-if="missPayload" class="tool-miss-note">
          检索没有命中可引用的政策片段（<code class="mono">hit=false</code>），本轮不会产出引用。
        </p>
      </div>
    </div>
  </details>
</template>

<style scoped>
.tool-trace {
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--paper);
  overflow: hidden;
  margin-bottom: 8px;
}

.tool-trace--error {
  border-color: var(--oxblood-line);
}

.tool-trace summary {
  list-style: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 11px;
  font-size: 11.5px;
  color: var(--ink-soft);
  font-weight: 600;
  user-select: none;
}

.tool-trace summary::-webkit-details-marker {
  display: none;
}

.tool-trace summary .chev {
  color: var(--ink-faint);
  transition: transform 0.2s;
}

.tool-trace[open] summary .chev {
  transform: rotate(90deg);
}

.tool-name {
  color: var(--ink-soft);
}

.tool-raw {
  font-size: 9.5px;
  color: var(--ink-faint);
  font-weight: 400;
}

.tool-elapsed {
  font-size: 9.5px;
  color: var(--ink-faint);
  font-weight: 400;
}

.tool-trace summary .tag {
  margin-left: auto;
}

.steps {
  padding: 2px 11px 11px;
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.step-title {
  font-size: 10px;
  color: var(--ink-faint);
  margin-bottom: 3px;
}

.code {
  margin: 0;
  font-family: var(--mono);
  font-size: 10.5px;
  line-height: 1.65;
  color: var(--ink-soft);
  background: var(--paper-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--r-s);
  padding: 7px 9px;
  max-height: 220px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  scrollbar-width: thin;
}

.code--error {
  border-color: var(--oxblood-line);
  background: var(--oxblood-soft);
  color: var(--oxblood);
}

.pending {
  font-size: 11px;
  color: var(--ink-faint);
}

.tool-error-note,
.tool-miss-note {
  margin: 6px 0 0;
  font-size: 10.5px;
  line-height: 1.7;
}

.tool-error-note {
  color: var(--oxblood);
}

.tool-miss-note {
  color: var(--amber);
}

.tool-miss-note code {
  font-size: 10px;
  padding: 0 3px;
  border-radius: 3px;
  background: var(--paper-sunken);
}

@media (prefers-reduced-motion: reduce) {
  .tool-trace summary .chev {
    transition: none;
  }
}
</style>
