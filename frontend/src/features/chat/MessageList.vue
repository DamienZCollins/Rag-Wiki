<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import MessageBubble from './MessageBubble.vue'
import type { ChatMessage } from './types'

/**
 * 对话流。
 * 流式区域用 aria-live=polite 播报状态变化，正文逐块追加时不逐字播报（会读屏刷屏）。
 */

const props = defineProps<{
  messages: ChatMessage[]
  sessionSuffix: string
  busy: boolean
  /** 新会话：后端无历史接口，历史气泡不回显 */
  resumed: boolean
}>()

const emit = defineEmits<{
  'open-citation': [messageId: string, index: number, event: MouseEvent]
  retry: [message: ChatMessage]
  handoff: [message: ChatMessage]
  'retry-search': [message: ChatMessage]
}>()

const scrollRef = ref<HTMLElement | null>(null)
const innerRef = ref<HTMLElement | null>(null)

/** 只有用户停在底部附近才自动跟随，向上翻阅时不打断 */
let pinnedToBottom = true

function onScroll() {
  const el = scrollRef.value
  if (!el) return
  pinnedToBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

async function scrollToBottom(force = false) {
  if (!force && !pinnedToBottom) return
  await nextTick()
  const el = scrollRef.value
  if (el) el.scrollTop = el.scrollHeight
}

/**
 * 内容高度变化就跟随。
 * 只靠 watch 消息字段不够：终态的收尾组件、抽屉提示、图片等插入后高度还会变一次，
 * 那时没有字段变化，流就停在半截（实测终态后残留 26px）。
 */
let observer: ResizeObserver | null = null

onMounted(() => {
  if (!innerRef.value) return
  observer = new ResizeObserver(() => {
    void scrollToBottom()
  })
  observer.observe(innerRef.value)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
})

// 消息数量变化（新一轮、终态追加）一定跟到底
watch(
  () => props.messages.length,
  () => {
    pinnedToBottom = true
    void scrollToBottom(true)
  },
)

// 正文增量只跟随，不强制拉回
watch(
  () => {
    const last = props.messages[props.messages.length - 1]
    return last ? `${last.id}:${last.text.length}:${last.tools.length}:${last.thinking}` : ''
  },
  () => {
    void scrollToBottom()
  },
)

const liveStatus = computed(() => {
  const last = props.messages[props.messages.length - 1]
  if (!last || last.role !== 'assistant') return ''
  if (last.thinking) return '正在思考'
  if (last.streaming) return '正在生成回答'
  // 终态优先：拒答是终态的一种，不能盖过 MAX_STEPS / FAILED / CANCELLED 的播报
  if (last.finishReason === 'MAX_STEPS') return '本轮达到步数上限，建议转人工'
  if (last.finishReason === 'FAILED') return '本轮失败'
  if (last.finishReason === 'CANCELLED' || last.canceled) return '本轮已中断'
  if (last.disconnectReason) return '连接中断'
  if (last.refused) return '检索未找到依据，已拒答'
  if (last.indexNotReady) return '索引未就绪，需要重建索引'
  if (last.finishReason === 'COMPLETED') return '本轮回答完成'
  return ''
})
</script>

<template>
  <div ref="scrollRef" class="thread" data-testid="thread" @scroll.passive="onScroll">
    <div ref="innerRef" class="thread-inner">
      <p class="sr-only" role="status" aria-live="polite">{{ liveStatus }}</p>

      <EmptyState
        v-if="messages.length === 0"
        title="从下面开始提问"
        description="点候选问题走快通道直答（零模型调用），或直接在输入框里描述客户问题走 RAG 检索。"
      >
        <p v-if="resumed" class="resume-note">
          <AppIcon name="info" :size="13" />
          本会话此前轮次不在页面上回显，后端会带上上下文继续对话。
        </p>
      </EmptyState>

      <template v-else>
        <p v-if="resumed" class="resume-note resume-note--top">
          <AppIcon name="info" :size="13" />
          本会话此前轮次不在页面上回显（后端无历史消息接口），后端会带上上下文继续对话。
        </p>
        <MessageBubble
          v-for="message in messages"
          :key="message.id"
          :message="message"
          :busy="busy"
          @open-citation="(id, index, event) => emit('open-citation', id, index, event)"
          @retry="(item) => emit('retry', item)"
          @handoff="(item) => emit('handoff', item)"
          @retry-search="(item) => emit('retry-search', item)"
        />
      </template>
    </div>
  </div>
</template>

<style scoped>
.thread {
  flex: 1;
  overflow: auto;
  min-height: 0;
  padding: 20px clamp(20px, 4vw, 52px) 28px;
  scrollbar-width: thin;
  background:
    linear-gradient(var(--paper-raised) 30%, transparent),
    linear-gradient(transparent, var(--paper-raised) 70%) 0 100%,
    radial-gradient(farthest-side at 50% 0, color-mix(in srgb, var(--oxblood) 3.5%, transparent), transparent),
    var(--paper-raised);
}

.thread-inner {
  max-width: 730px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 13px;
}

.resume-note {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  font-size: 10.5px;
  color: var(--ink-mute);
  background: var(--paper);
  border: 1px dashed var(--line-strong);
  border-radius: 7px;
  padding: 6px 10px;
  line-height: 1.6;
}

.resume-note--top {
  align-self: center;
}

.resume-note .icon {
  color: var(--ink-faint);
  flex: none;
}

@media (max-width: 760px) {
  .thread {
    padding: 16px 14px 22px;
  }
}
</style>
