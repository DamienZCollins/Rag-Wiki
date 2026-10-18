<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import { useSessionStore, type LocalSession } from '@/stores/session'

/**
 * 本地会话队列。
 *
 * 后端没有会话列表接口，这里只维护当前浏览器内的最近会话：
 * 只显示 sessionId 后四位与首句摘要，不伪造客户姓名、头像与工单信息。
 */

const emit = defineEmits<{
  select: [sessionId: string]
  create: []
  remove: [sessionId: string]
}>()

const props = defineProps<{ currentSessionId: string }>()

const sessionStore = useSessionStore()

const sessions = computed<LocalSession[]>(() => sessionStore.sessions)

function suffix(id: string): string {
  return id.slice(-4)
}

function timeLabel(at: number): string {
  const diff = Date.now() - at
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  const date = new Date(at)
  return `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}
</script>

<template>
  <aside class="rail" aria-label="本地会话队列">
    <div class="rail-head">
      <div class="rail-title-row">
        <h2 class="rail-title">本地会话</h2>
        <button class="icon-btn" type="button" aria-label="新建会话" title="新建会话" @click="emit('create')">
          <AppIcon name="plus" :size="16" />
        </button>
      </div>
      <div class="rail-sub">
        <span>当前浏览器 · 最多 20 条</span>
      </div>
    </div>

    <ul v-if="sessions.length > 0" class="queue">
      <li
        v-for="item in sessions"
        :key="item.sessionId"
        class="queue-item"
        data-testid="session-item"
        :aria-current="item.sessionId === props.currentSessionId ? 'true' : undefined"
        tabindex="0"
        @click="emit('select', item.sessionId)"
        @keydown.enter.prevent="emit('select', item.sessionId)"
        @keydown.space.prevent="emit('select', item.sessionId)"
      >
        <span class="q-mark mono" aria-hidden="true">#{{ suffix(item.sessionId) }}</span>
        <div class="q-main">
          <div class="q-top">
            <span class="q-id mono">会话 …{{ suffix(item.sessionId) }}</span>
            <span class="q-time">{{ timeLabel(item.lastActiveAt) }}</span>
          </div>
          <div class="q-msg">{{ item.preview || '（还没有提问）' }}</div>
          <div class="q-tags">
            <!-- 会话状态只有两种：进行中 / 已转人工。不要复用条目的「生效/失效」词 -->
            <span class="tag" :class="item.status === 'handoff' ? 'amber' : 'plain'">
              {{ item.status === 'handoff' ? '已转人工' : '进行中' }}
            </span>
          </div>
        </div>
        <button
          class="icon-btn q-remove"
          type="button"
          :aria-label="`从列表移除会话 …${suffix(item.sessionId)}`"
          @click.stop="emit('remove', item.sessionId)"
        >
          <AppIcon name="trash" :size="13" />
        </button>
      </li>
    </ul>

    <div v-else class="rail-empty">
      <p>还没有本地会话记录。</p>
      <p class="rail-empty-sub">第一次提问后会自动记录，只存 sessionId 与首句摘要。</p>
    </div>

    <div class="rail-foot">
      <span>显示 {{ sessions.length }} / 20</span>
      <span class="mono">localStorage</span>
    </div>
  </aside>
</template>

<style scoped>
.rail {
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--paper);
  border-right: 1px solid var(--line);
}

.rail-head {
  padding: 14px 16px 10px;
}

.rail-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.rail-title {
  font-family: var(--serif);
  font-size: 16px;
  font-weight: 700;
  margin: 0;
}

.rail-sub {
  font-size: 11px;
  color: var(--ink-mute);
  margin-top: 3px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.queue {
  list-style: none;
  margin: 0;
  padding: 0 10px 10px;
  overflow: auto;
  flex: 1;
  min-height: 0;
  scrollbar-width: thin;
}

.queue-item {
  position: relative;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 9px;
  align-items: start;
  padding: 11px 10px;
  border-radius: 9px;
  cursor: pointer;
  border-bottom: 1px solid var(--line-soft);
  transition: background 0.15s;
}

.queue-item:last-child {
  border-bottom: 0;
}

.queue-item:hover {
  background: var(--paper-deep);
}

.queue-item[aria-current='true'] {
  background: var(--paper-raised);
  box-shadow: 0 0 0 1px var(--line);
  border-bottom-color: transparent;
}

.queue-item[aria-current='true']::before {
  content: '';
  position: absolute;
  left: 0;
  top: 12px;
  bottom: 12px;
  width: 2px;
  border-radius: 2px;
  background: var(--oxblood);
}

.q-mark {
  font-size: 10px;
  color: var(--ink-faint);
  border: 1px solid var(--line);
  border-radius: 5px;
  padding: 1px 5px;
  margin-top: 1px;
  background: var(--paper-raised);
}

.q-main {
  min-width: 0;
}

.q-top {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.q-id {
  font-size: 10.5px;
  color: var(--ink-soft);
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.q-time {
  font-size: 10px;
  color: var(--ink-faint);
  font-family: var(--mono);
  white-space: nowrap;
  margin-left: auto;
}

.q-msg {
  font-size: 11.5px;
  color: var(--ink-mute);
  margin-top: 3px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.q-tags {
  display: flex;
  gap: 4px;
  margin-top: 6px;
  flex-wrap: wrap;
}

.q-remove {
  width: 24px;
  height: 24px;
  opacity: 0;
  transition: opacity 0.15s;
}

.queue-item:hover .q-remove,
.q-remove:focus-visible {
  opacity: 1;
}

.rail-empty {
  flex: 1;
  padding: 18px 16px;
  font-size: 11.5px;
  color: var(--ink-mute);
}

.rail-empty p {
  margin: 0 0 5px;
}

.rail-empty-sub {
  color: var(--ink-faint);
  font-size: 10.5px;
  line-height: 1.7;
}

.rail-foot {
  padding: 9px 16px;
  border-top: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 10.5px;
  color: var(--ink-faint);
}

.rail-foot .mono {
  font-size: 10px;
}
</style>
