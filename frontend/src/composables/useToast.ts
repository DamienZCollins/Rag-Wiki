import { ref } from 'vue'

/** 轻量 Toast。同一时刻最多 3 条，默认 3 秒消失，可手动关闭 */
export type ToastKind = 'success' | 'error' | 'info'

export interface ToastItem {
  id: number
  kind: ToastKind
  message: string
  timer: ReturnType<typeof setTimeout> | null
}

const MAX_TOASTS = 3
const DURATION_MS = 3000

const items = ref<ToastItem[]>([])
let seq = 0

function remove(id: number) {
  const index = items.value.findIndex((item) => item.id === id)
  if (index < 0) return
  const target = items.value[index]
  if (target.timer) clearTimeout(target.timer)
  items.value.splice(index, 1)
}

function push(kind: ToastKind, message: string, durationMs = DURATION_MS) {
  const item: ToastItem = { id: ++seq, kind, message, timer: null }
  items.value.push(item)
  if (items.value.length > MAX_TOASTS) {
    const overflow = items.value.shift()
    if (overflow?.timer) clearTimeout(overflow.timer)
  }
  item.timer = setTimeout(() => remove(item.id), durationMs)
  return item.id
}

export function useToast() {
  return {
    items,
    success: (message: string) => push('success', message),
    error: (message: string) => push('error', message),
    info: (message: string) => push('info', message),
    dismiss: remove,
  }
}
