<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import AppLayout from '@/layouts/AppLayout.vue'
import CandidatePanel from '@/features/chat/CandidatePanel.vue'
import CitationDrawer from '@/features/chat/CitationDrawer.vue'
import Composer from '@/features/chat/Composer.vue'
import ContextPanel from '@/features/chat/ContextPanel.vue'
import MessageList from '@/features/chat/MessageList.vue'
import SessionList from '@/features/chat/SessionList.vue'
import { useChatSession } from '@/features/chat/useChatSession'
import type { ChatMessage } from '@/features/chat/types'

/**
 * 售后对话视图。
 * 三栏：本地会话队列 / 对话区 / 上下文面板（引用 · 链路）。
 * 原型的「客户档案」栏没有数据源，这里不做，右栏只保留两个真实可用的 Tab。
 */

const chat = useChatSession()
const composerRef = ref<InstanceType<typeof Composer> | null>(null)

onMounted(() => {
  chat.bootstrap()
  composerRef.value?.focus()
})

/** 会话切换后把焦点放回输入框，保持键盘连续 */
watch(
  () => chat.sessionId.value,
  () => {
    composerRef.value?.focus()
  },
)

const contextText = computed(() => {
  const parts = [`会话 …${chat.sessionSuffix.value}`]
  if (chat.streaming.value) parts.push('生成中')
  if (chat.resumed.value) parts.push('续聊（历史不回显）')
  return parts.join(' · ')
})

function onOpenCitation(messageId: string, index: number, event: MouseEvent) {
  chat.openCitation(messageId, index, event)
}

function onOpenActiveCitation(index: number, event: MouseEvent) {
  const message = chat.lastAssistant.value
  if (!message) return
  chat.openCitation(message.id, index, event)
}

function onRetry(message: ChatMessage) {
  chat.retry(message)
}

function onRetrySearch(message: ChatMessage) {
  chat.retrySearch(message)
}

function onHandoff(message: ChatMessage) {
  chat.handoff(message)
}

function onSend(text: string) {
  chat.submitChat(text)
}

function onPick(entry: Parameters<typeof chat.submitCandidate>[0]) {
  void chat.submitCandidate(entry)
}
</script>

<template>
  <AppLayout>
    <template #context>{{ contextText }}</template>

    <div class="conv-layout">
      <SessionList
        :current-session-id="chat.sessionId.value"
        @select="chat.selectSession"
        @create="chat.newSession"
        @remove="chat.removeSession"
      />

      <section class="conversation" aria-label="会话详情">
        <header class="conv-head">
          <div class="conv-user">
            <div class="conv-mark" aria-hidden="true">话</div>
            <div class="conv-user-text">
              <div class="conv-name">
                会话 …{{ chat.sessionSuffix.value }}
                <span class="tag" :class="chat.streaming.value ? 'amber' : 'plain'">
                  {{ chat.streaming.value ? '生成中' : '本地会话' }}
                </span>
              </div>
              <div class="conv-meta">
                <span class="mono">{{ chat.sessionId.value }}</span>
                <span class="dot-sep"></span>
                <span>{{ chat.messages.value.length }} 条消息</span>
              </div>
            </div>
          </div>
          <div class="conv-actions">
            <button class="btn ghost sm" type="button" @click="chat.newSession()">
              新建会话
            </button>
            <button
              class="btn sm"
              type="button"
              :disabled="chat.streaming.value"
              @click="chat.handoff(chat.lastAssistant.value)"
            >
              转人工
            </button>
          </div>
        </header>

        <MessageList
          :messages="chat.messages.value"
          :session-suffix="chat.sessionSuffix.value"
          :busy="chat.streaming.value"
          :resumed="chat.resumed.value"
          @open-citation="onOpenCitation"
          @retry="onRetry"
          @handoff="onHandoff"
          @retry-search="onRetrySearch"
        />

        <div class="candidate-slot">
          <CandidatePanel
            :entries="chat.candidates.value"
            :visible-entries="chat.visibleCandidates.value"
            :expanded="chat.candidatesShowAll.value"
            :phase="chat.candidatesPhase.value"
            :error="chat.candidatesError.value"
            :busy="chat.streaming.value"
            :collapsed="chat.candidatesCollapsed.value"
            @pick="onPick"
            @toggle-all="chat.toggleShowAll()"
            @open-zone="chat.toggleCandidates()"
            @close-zone="chat.toggleCandidates()"
            @reload="chat.loadCandidates()"
          />
        </div>

        <Composer
          ref="composerRef"
          :busy="chat.streaming.value"
          @send="onSend"
          @stop="chat.stop()"
        />
      </section>

      <ContextPanel
        :message="chat.lastAssistant.value"
        :citations="chat.activeCitations.value"
        :tab="chat.contextTab.value"
        @update:tab="(tab) => (chat.contextTab.value = tab)"
        @open-citation="onOpenActiveCitation"
      />
    </div>

    <CitationDrawer
      :open="chat.drawer.value.open"
      :citation="chat.drawer.value.citation"
      :index="chat.drawer.value.index"
      :invalid="chat.drawer.value.invalid"
      :max-index="chat.drawer.value.maxIndex"
      :score-note="chat.drawer.value.scoreNote"
      @close="chat.closeDrawer()"
    />
  </AppLayout>
</template>

<style scoped>
.conv-layout {
  display: grid;
  grid-template-columns: 282px minmax(0, 1fr) 316px;
  min-height: 0;
  height: 100%;
  overflow: hidden;
}

.conversation {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: hidden;
  background: var(--paper-raised);
}

.conv-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 22px;
  border-bottom: 1px solid var(--line);
  min-height: 66px;
  background: var(--paper-raised);
  flex: none;
}

.conv-user {
  display: flex;
  align-items: center;
  gap: 11px;
  min-width: 0;
}

.conv-mark {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  flex: none;
  display: grid;
  place-items: center;
  background: var(--paper-deep);
  border: 1px solid var(--line);
  color: var(--ink-soft);
  font-family: var(--serif);
  font-size: 15px;
  font-weight: 700;
}

.conv-user-text {
  min-width: 0;
}

.conv-name {
  font-size: 14px;
  font-weight: 700;
  line-height: 1.3;
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
}

.conv-meta {
  font-size: 11px;
  color: var(--ink-mute);
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 2px;
  flex-wrap: wrap;
  min-width: 0;
}

.conv-meta .mono {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 260px;
}

.conv-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}

.candidate-slot {
  flex: none;
  padding: 0 clamp(20px, 4vw, 52px) 10px;
  max-width: 730px;
  width: 100%;
  margin: 0 auto;
}

@media (max-width: 1280px) {
  .conv-layout {
    grid-template-columns: 262px minmax(0, 1fr) 300px;
  }
}

@media (max-width: 1080px) {
  .conv-layout {
    grid-template-columns: 250px minmax(0, 1fr);
  }
}

@media (max-width: 760px) {
  .conv-layout {
    grid-template-columns: 1fr;
  }

  /* 760 以下隐藏会话队列，把宽度让给对话区 */
  .conv-layout > :deep(.rail) {
    display: none;
  }

  .conv-head {
    padding: 10px 14px;
    flex-wrap: wrap;
  }

  .conv-meta .mono {
    max-width: 140px;
  }

  .candidate-slot {
    padding: 0 14px 10px;
  }
}
</style>
