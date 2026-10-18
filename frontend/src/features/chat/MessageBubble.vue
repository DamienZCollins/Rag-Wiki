<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import HandoffNote from './HandoffNote.vue'
import IndexNotReady from './IndexNotReady.vue'
import RefusalState from './RefusalState.vue'
import RichText from './RichText.vue'
import ThinkingIndicator from './ThinkingIndicator.vue'
import ToolCallCard from './ToolCallCard.vue'
import type { ChatMessage } from './types'

/**
 * 一轮消息的渲染。
 *
 * 终态在这里统一收口，且四种收尾的视觉与文案互不相同：
 * COMPLETED 正常 / MAX_STEPS 转人工（琥珀，不是红色报错）/ CANCELLED 已中断可重试 / FAILED 报错带 code。
 * 断流（没收到 FINISHED）与业务错误分开写，避免用户把网络问题当成模型拒答。
 */

const props = defineProps<{
  message: ChatMessage
  /** 同一会话正在生成，禁用重试等动作 */
  busy: boolean
}>()

const emit = defineEmits<{
  'open-citation': [messageId: string, index: number, event: MouseEvent]
  retry: [message: ChatMessage]
  handoff: [message: ChatMessage]
  'retry-search': [message: ChatMessage]
}>()

const isUser = computed(() => props.message.role === 'user')
const isAssistant = computed(() => props.message.role === 'assistant')

const elapsedLabel = computed(() => {
  if (!props.message.events.length) return null
  const last = props.message.events[props.message.events.length - 1]
  return last.at < 1000 ? `${last.at} ms` : `${(last.at / 1000).toFixed(1)} s`
})

const channelLabel = computed(() => {
  if (props.message.channel === 'fastpath') return '快通道直答'
  if (props.message.channel === 'rag') return 'RAG 回答'
  return '售后助手'
})

const channelTone = computed(() => (props.message.channel === 'fastpath' ? 'teal' : 'blue'))

/** 快通道命中：tokenIn/tokenOut 都是 0，是「零模型调用」的佐证 */
const zeroTokens = computed(
  () => props.message.tokenIn === 0 && props.message.tokenOut === 0 && props.message.finishReason === 'COMPLETED',
)

/**
 * 拒答卡：只在「本轮确实没有产出任何可引用片段」时出现。
 *
 * 不能只看检索侧的 refused：Agent 会改写问题并多次检索，
 * 复算用的某一次问题可能被拒答，而工具实际已返回了别的片段并据此作答。
 * 那种情况已经有引用可追溯，再盖一张拒答卡自相矛盾。
 */
const citationCount = computed(() =>
  props.message.citationLists.reduce((total, list) => total + list.items.length, 0),
)

const showRefusal = computed(
  () =>
    isAssistant.value &&
    !props.message.streaming &&
    props.message.finishReason !== null &&
    props.message.refused &&
    citationCount.value === 0,
)

const showIndexNotReady = computed(
  () => isAssistant.value && props.message.indexNotReady && !props.message.streaming,
)

/** 检索报错：与拒答、与索引未就绪都不同 */
const showSearchError = computed(
  () =>
    isAssistant.value &&
    Boolean(props.message.searchError) &&
    !props.message.streaming &&
    !props.message.refused &&
    !props.message.indexNotReady,
)

const showDisconnect = computed(
  () => isAssistant.value && props.message.disconnectReason !== null && !props.message.streaming,
)

/** 已中断：用户主动停止（canceled）或服务端以 CANCELLED 收尾，两者都要提示且可重试 */
const showCanceled = computed(
  () =>
    isAssistant.value &&
    !props.message.streaming &&
    (props.message.canceled || props.message.finishReason === 'CANCELLED'),
)

const showBusy = computed(() => isAssistant.value && props.message.busyConflict)

const showFailed = computed(
  () =>
    isAssistant.value &&
    !props.message.streaming &&
    props.message.finishReason === 'FAILED' &&
    !props.message.busyConflict,
)

const showMaxSteps = computed(
  () => isAssistant.value && props.message.handoffSuggested && !props.message.streaming,
)

const canRetry = computed(
  () =>
    isAssistant.value &&
    !props.busy &&
    props.message.request?.kind === 'chat' &&
    (props.message.finishReason === 'FAILED' ||
      props.message.finishReason === 'CANCELLED' ||
      props.message.canceled ||
      props.message.disconnectReason !== null ||
      props.message.errors.length > 0),
)

/** 快通道命中：点候选直接作答，或自由输入时 /api/chat 内部先试快通道后命中 */
const isFastHit = computed(
  () =>
    isAssistant.value &&
    props.message.text.length > 0 &&
    (props.message.fastAnswer !== null ||
      (props.message.channel === 'fastpath' &&
        props.message.finishReason === 'COMPLETED' &&
        props.message.tools.length === 0)),
)

const showEmptyAnswer = computed(
  () =>
    isAssistant.value &&
    !props.message.streaming &&
    !props.message.thinking &&
    props.message.text.trim().length === 0 &&
    props.message.tools.length === 0 &&
    props.message.finishReason === 'COMPLETED' &&
    !props.message.refused &&
    !props.message.toolMiss,
)

const searchScoresUnavailable = computed(
  () => props.message.search === null || props.message.searchError !== null,
)

const timeLabel = computed(() => {
  const date = new Date(props.message.createdAt)
  const hh = String(date.getHours()).padStart(2, '0')
  const mm = String(date.getMinutes()).padStart(2, '0')
  return `${hh}:${mm}`
})
</script>

<template>
  <div v-if="isUser" class="msg-block user-block">
    <div class="msg-head">
      <span class="who">你</span>
      <span class="msg-time">{{ timeLabel }}</span>
    </div>
    <div class="bubble">{{ message.text }}</div>
  </div>

  <div v-else-if="isAssistant" class="msg-block bot-block" data-testid="message-assistant">
    <div class="msg-head">
      <span class="who">售后助手</span>
      <span class="tag" :class="channelTone">{{ channelLabel }}</span>
      <span v-if="message.routedFrom === 'fastpath'" class="tag amber">已转检索</span>
      <span class="msg-time">{{ timeLabel }}</span>
    </div>

    <!-- 工具过程：默认收起，按 callId 各自独立 -->
    <ToolCallCard v-for="tool in message.tools" :key="tool.callId" :trace="tool" />

    <div v-if="message.thinking" class="thinking-slot">
      <ThinkingIndicator />
    </div>

    <!-- 快通道直答：命中即答，全程没有思考态与工具卡片。
         两个入口都会命中：点候选直接调 /api/fastpath/answer（带 fastAnswer 元信息），
         自由输入则 /api/chat 内部先试快通道（没有元信息，但 token 为 0 同样是零调用佐证）。 -->
    <div v-if="isFastHit" class="bubble copilot">
      <div class="fast-answer">
        <div class="fast-top">
          <AppIcon name="bolt" :size="14" />
          命中已发布条目 · 0 次模型调用
          <span v-if="message.fastAnswer" class="mono fast-top-right">
            {{ message.fastAnswer.elapsedMs }} ms
          </span>
        </div>
        <div v-if="message.fastAnswer?.question" class="fast-title">
          {{ message.fastAnswer.question }}
        </div>
        <RichText
          class="fast-text"
          :text="message.text"
          :citation-lists="message.citationLists"
          @open-citation="(index, event) => emit('open-citation', message.id, index, event)"
        />
        <div class="fast-foot">
          <span v-if="message.fastAnswer?.source" class="tag teal">
            来源：{{ message.fastAnswer.source }}
          </span>
          <span v-if="message.fastAnswer" class="mono">
            confidence {{ message.fastAnswer.confidence.toFixed(2) }}
          </span>
          <span class="mono">tokenIn {{ message.tokenIn }} · tokenOut {{ message.tokenOut }}</span>
        </div>
      </div>
    </div>

    <!-- RAG 正文 -->
    <div v-else-if="message.text" class="bubble copilot">
      <div v-if="message.channel === 'rag'" class="answer-lead">
        <AppIcon name="doc" :size="13" />
        <span>回答依据检索到的政策片段，引用角标可展开核对</span>
      </div>
      <RichText
        :text="message.text"
        :citation-lists="message.citationLists"
        @open-citation="(index, event) => emit('open-citation', message.id, index, event)"
      />
    </div>

    <div v-if="message.routedFrom === 'fastpath' && message.channel === 'rag'" class="ghost-note">
      <AppIcon name="bolt-off" :size="14" />
      <span>快通道未命中，已自动转检索。这一轮会调用模型与向量检索。</span>
    </div>

    <!-- 拒答：两路分数都低于阈值 -->
    <RefusalState
      v-if="showRefusal"
      :top-bm25-score="message.search?.topBm25Score ?? null"
      :top-vector-score="message.search?.topVectorScore ?? null"
      :scores-unavailable="searchScoresUnavailable"
      :question="message.search?.question ?? null"
      :search-count="message.searches.length"
      @handoff="emit('handoff', message)"
    />

    <!-- 4001：索引未就绪，不是拒答 -->
    <IndexNotReady
      v-if="showIndexNotReady"
      :detail="message.searchError"
      @retry="emit('retry-search', message)"
    />

    <!-- 检索报错：第三种情况，与上面两种文案不同 -->
    <div v-if="showSearchError" class="search-error" role="alert">
      <AppIcon name="alert" :size="16" />
      <div>
        <div class="search-error-title">检索过程出错</div>
        <p class="search-error-text">{{ message.searchError }}</p>
        <p class="search-error-text">
          这是检索侧失败，不是知识库里没有依据（拒答），也不是索引未建（4001）。
        </p>
        <button class="btn sm" type="button" :disabled="busy" @click="emit('retry-search', message)">
          重试检索
        </button>
      </div>
    </div>

    <!-- 流内错误：不终止流，FINISHED 之前一直可见 -->
    <div v-for="(error, index) in message.errors" :key="`${error.code}-${index}`" class="stream-error" role="alert">
      <AppIcon name="alert" :size="15" />
      <div>
        <div class="stream-error-title">流内错误 · code={{ error.code }}</div>
        <p class="stream-error-text">{{ error.message }}</p>
      </div>
    </div>

    <!-- 会话繁忙：ERROR(2002)，不是普通错误 -->
    <div v-if="showBusy" class="busy-note" role="alert">
      <AppIcon name="info" :size="15" />
      <div>
        <div class="busy-title">该会话有回合正在进行</div>
        <p class="busy-text">
          后端拒绝了这次请求（流内 ERROR code=2002 + FINISHED FAILED），这一轮没有执行。
          等上一轮结束后再发送即可。
        </p>
      </div>
    </div>

    <!-- 断流：与业务错误分开写 -->
    <div v-if="showDisconnect" class="disconnect-note" role="alert">
      <AppIcon name="warning" :size="15" />
      <div>
        <div class="disconnect-title">连接中断，本轮没有收到结束信号</div>
        <p class="disconnect-text">
          这是网络或连接层的问题，不是模型返回的错误，也不是知识库拒答。
          已接收到的正文保留在上面，可以重试。
        </p>
      </div>
    </div>

    <!-- CANCELLED / 主动停止 -->
    <div v-if="showCanceled" class="canceled-note">
      <AppIcon name="stop" :size="14" />
      <span>
        已中断生成（FINISHED reason=CANCELLED）。已输出的正文保留，可重试。
      </span>
    </div>

    <!-- MAX_STEPS：转人工，不是红色报错 -->
    <HandoffNote
      v-if="showMaxSteps"
      :done="message.handoffDone"
      reason="max-steps"
      @handoff="emit('handoff', message)"
    />

    <!-- FAILED -->
    <div v-if="showFailed" class="failed-note" role="alert">
      <AppIcon name="alert" :size="16" />
      <div>
        <div class="failed-title">
          本轮失败（FINISHED reason=FAILED）
        </div>
        <p class="failed-text">
          {{ message.errors.length > 0 ? message.errors.map((item) => `${item.code}：${item.message}`).join('；') : '后端没有给出具体错误码，可重试一次。' }}
        </p>
      </div>
    </div>

    <div v-if="showEmptyAnswer" class="ghost-note">
      <AppIcon name="info" :size="14" />
      <span>本轮结束但正文为空：后端返回 COMPLETED 却没有输出分片，可重试。</span>
    </div>

    <!-- 工具失败时提示整轮未失败 -->
    <p v-if="message.tools.some((tool) => tool.status === 'error') && !message.streaming" class="tool-fail-hint">
      有工具调用失败，但整轮对话没有因此失败：失败信息已回传给模型。
    </p>

    <div v-if="isAssistant && !message.streaming && message.finishReason" class="answer-foot">
      <button
        v-if="canRetry"
        class="btn ghost sm"
        type="button"
        :disabled="busy"
        @click="emit('retry', message)"
      >
        <AppIcon name="refresh" :size="13" />
        重试
      </button>
      <button
        v-if="message.finishReason === 'MAX_STEPS' && !message.handoffDone"
        class="btn ghost sm"
        type="button"
        @click="emit('handoff', message)"
      >
        <AppIcon name="user" :size="13" />
        转人工
      </button>
      <span class="token mono">
        <template v-if="zeroTokens">快通道 · 0 tokens</template>
        <template v-else>tokenIn {{ message.tokenIn }} · tokenOut {{ message.tokenOut }}</template>
        <template v-if="elapsedLabel"> · {{ elapsedLabel }}</template>
      </span>
    </div>
  </div>
</template>

<style scoped>
.msg-block {
  display: flex;
  flex-direction: column;
  gap: 7px;
  animation: msgIn 0.3s var(--ease);
}

@keyframes msgIn {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.msg-head {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 11px;
  color: var(--ink-mute);
  flex-wrap: wrap;
}

.who {
  font-weight: 700;
  color: var(--ink-soft);
  font-size: 11.5px;
}

.msg-head .tag {
  height: 17px;
  font-size: 9.5px;
  line-height: 15px;
}

.msg-time {
  font-family: var(--mono);
  font-size: 10px;
  color: var(--ink-faint);
}

.bubble {
  border-radius: 12px;
  padding: 12px 16px;
  font-size: 13.5px;
  line-height: 1.75;
  color: var(--ink);
}

.user-block {
  align-self: flex-end;
  max-width: 86%;
}

.user-block .msg-head {
  justify-content: flex-end;
}

.user-block .bubble {
  background: var(--paper-sunken);
  border: 1px solid var(--line);
  border-top-right-radius: 4px;
  white-space: pre-wrap;
  word-break: break-word;
}

.bot-block {
  max-width: 100%;
}

.bot-block .bubble {
  background: var(--paper-raised);
  border: 1px solid var(--line);
  border-top-left-radius: 4px;
}

.bot-block .bubble.copilot {
  border-left: 2px solid var(--teal);
}

.thinking-slot {
  max-width: 100%;
}

.answer-lead {
  font-size: 10.5px;
  color: var(--ink-faint);
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 9px;
  padding-bottom: 8px;
  border-bottom: 1px dashed var(--line);
}

.answer-lead .icon {
  color: var(--teal);
}

/* 快通道答案 */
.fast-answer {
  border: 1px solid var(--teal-line);
  background: linear-gradient(180deg, var(--teal-soft), var(--paper-raised) 75%);
  border-radius: 10px;
  padding: 14px 16px;
  margin: -2px;
}

.fast-top {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 10.5px;
  color: var(--teal);
  font-weight: 600;
}

.fast-top-right {
  margin-left: auto;
  font-size: 9.5px;
  color: var(--ink-faint);
  font-weight: 400;
}

.fast-title {
  font-size: 14px;
  font-weight: 700;
  margin: 5px 0 2px;
  color: var(--ink);
}

.fast-text {
  font-size: 13px;
  color: var(--ink);
  line-height: 1.75;
}

.fast-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  padding-top: 9px;
  border-top: 1px dashed var(--teal-line);
  font-size: 10.5px;
  color: var(--ink-mute);
  flex-wrap: wrap;
}

.ghost-note {
  font-size: 11.5px;
  color: var(--ink-mute);
  background: var(--paper);
  border: 1px dashed var(--line-strong);
  border-radius: 8px;
  padding: 8px 11px;
  display: flex;
  gap: 7px;
  align-items: flex-start;
}

.ghost-note .icon {
  margin-top: 3px;
  color: var(--amber);
  flex: none;
}

.search-error,
.stream-error,
.busy-note,
.disconnect-note,
.failed-note {
  display: flex;
  gap: 9px;
  align-items: flex-start;
  border-radius: 9px;
  padding: 10px 12px;
  font-size: 11.5px;
}

.search-error,
.stream-error,
.failed-note {
  border: 1px solid var(--oxblood-line);
  background: var(--oxblood-soft);
  color: var(--oxblood);
}

.busy-note {
  border: 1px solid var(--blue-line);
  background: var(--blue-soft);
  color: var(--blue);
}

.disconnect-note {
  border: 1px solid var(--amber-line);
  background: var(--amber-soft);
  color: var(--amber);
}

.search-error .icon,
.stream-error .icon,
.busy-note .icon,
.disconnect-note .icon,
.failed-note .icon {
  margin-top: 2px;
  flex: none;
}

.search-error-title,
.stream-error-title,
.busy-title,
.disconnect-title,
.failed-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--ink);
}

.search-error-text,
.stream-error-text,
.busy-text,
.disconnect-text,
.failed-text {
  margin: 3px 0 0;
  line-height: 1.75;
  color: var(--ink-soft);
}

.search-error-text + .btn {
  margin-top: 8px;
}

.canceled-note {
  display: flex;
  gap: 7px;
  align-items: center;
  font-size: 11px;
  color: var(--ink-mute);
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 7px 10px;
}

.canceled-note .icon {
  color: var(--ink-faint);
}

.tool-fail-hint {
  margin: 0;
  font-size: 10.5px;
  color: var(--ink-mute);
}

.answer-foot {
  display: flex;
  align-items: center;
  gap: 3px;
  margin-top: 2px;
  padding-left: 2px;
  flex-wrap: wrap;
}

.answer-foot .btn {
  color: var(--ink-faint);
}

.answer-foot .btn:hover:not(:disabled) {
  color: var(--ink);
}

.answer-foot .token {
  margin-left: auto;
  font-size: 10px;
  color: var(--ink-faint);
}

@media (prefers-reduced-motion: reduce) {
  .msg-block {
    animation: none;
  }
}
</style>
