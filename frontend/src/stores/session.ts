import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'

/**
 * 本地会话留存。
 * 后端没有会话列表接口，这里只维护当前浏览器内的会话：
 * sessionId、首句、最后活跃时间、是否已转人工。
 * 不伪造客户姓名、头像与工单信息。
 */

export type SessionStatus = 'active' | 'handoff'

export interface LocalSession {
  sessionId: string
  /** 首条用户消息，截断到 60 字 */
  preview: string
  lastActiveAt: number
  status: SessionStatus
}

const STORAGE_KEY = 'ragwiki.sessions'
const MAX_SESSIONS = 20

function newSessionId(): string {
  const stamp = Date.now().toString(36)
  const rand = Math.random().toString(36).slice(2, 6)
  return `s-${stamp}${rand}`
}

function readStored(): LocalSession[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as LocalSession[]
    if (!Array.isArray(parsed)) return []
    return parsed.filter(
      (item) => item && typeof item.sessionId === 'string' && item.sessionId.length > 0,
    )
  } catch {
    return []
  }
}

export const useSessionStore = defineStore('session', () => {
  const sessions = ref<LocalSession[]>(readStored())

  const sorted = computed(() =>
    [...sessions.value].sort((a, b) => b.lastActiveAt - a.lastActiveAt),
  )

  function persist() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(sessions.value))
    } catch {
      // 本地存储写满或不可用时静默降级，不影响对话
    }
  }

  watch(sessions, persist, { deep: true })

  /** 确保会话在列表中，并刷新活跃时间与首句 */
  function touch(sessionId: string, firstMessage?: string) {
    const existing = sessions.value.find((item) => item.sessionId === sessionId)
    if (existing) {
      existing.lastActiveAt = Date.now()
      if (firstMessage && !existing.preview) {
        existing.preview = truncate(firstMessage)
      }
      return
    }
    sessions.value.push({
      sessionId,
      preview: firstMessage ? truncate(firstMessage) : '',
      lastActiveAt: Date.now(),
      status: 'active',
    })
    evict()
  }

  function markHandoff(sessionId: string) {
    const existing = sessions.value.find((item) => item.sessionId === sessionId)
    if (existing) {
      existing.status = 'handoff'
      existing.lastActiveAt = Date.now()
    }
  }

  function remove(sessionId: string) {
    sessions.value = sessions.value.filter((item) => item.sessionId !== sessionId)
  }

  /** 超过 20 条淘汰最久未活跃的 */
  function evict() {
    if (sessions.value.length <= MAX_SESSIONS) return
    sessions.value.sort((a, b) => b.lastActiveAt - a.lastActiveAt)
    sessions.value = sessions.value.slice(0, MAX_SESSIONS)
  }

  function has(sessionId: string): boolean {
    return sessions.value.some((item) => item.sessionId === sessionId)
  }

  return {
    sessions: sorted,
    touch,
    markHandoff,
    remove,
    has,
    newSessionId,
  }
})

function truncate(text: string): string {
  const clean = text.replace(/\s+/g, ' ').trim()
  return clean.length > 60 ? `${clean.slice(0, 60)}…` : clean
}
