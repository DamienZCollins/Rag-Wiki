<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    message?: string
    confirmText?: string
    cancelText?: string
    /** 危险操作：确认按钮用告警色，并额外提示影响范围 */
    tone?: 'normal' | 'danger'
    /** 影响范围与预计耗时说明 */
    impact?: string
  }>(),
  { confirmText: '确认', cancelText: '取消', tone: 'normal' },
)

const emit = defineEmits<{
  confirm: []
  cancel: []
  'update:open': [value: boolean]
}>()

const dialogRef = ref<HTMLElement | null>(null)
const confirmRef = ref<HTMLButtonElement | null>(null)
let lastFocused: HTMLElement | null = null

const FOCUSABLE =
  'button:not(:disabled), [href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])'

function focusables(): HTMLElement[] {
  if (!dialogRef.value) return []
  return Array.from(dialogRef.value.querySelectorAll<HTMLElement>(FOCUSABLE))
}

function onKeydown(event: KeyboardEvent) {
  if (!props.open) return
  if (event.key === 'Escape') {
    event.preventDefault()
    emit('cancel')
    emit('update:open', false)
    return
  }
  if (event.key !== 'Tab') return
  // 焦点陷阱：Tab 不逃逸出弹窗
  const list = focusables()
  if (list.length === 0) {
    event.preventDefault()
    return
  }
  const first = list[0]
  const last = list[list.length - 1]
  const active = document.activeElement as HTMLElement | null
  if (event.shiftKey) {
    if (active === first || !dialogRef.value?.contains(active)) {
      event.preventDefault()
      last.focus()
    }
  } else if (active === last || !dialogRef.value?.contains(active)) {
    event.preventDefault()
    first.focus()
  }
}

watch(
  () => props.open,
  async (open) => {
    if (open) {
      lastFocused = document.activeElement as HTMLElement | null
      document.addEventListener('keydown', onKeydown, true)
      document.body.style.overflow = 'hidden'
      await nextTick()
      confirmRef.value?.focus()
    } else {
      document.removeEventListener('keydown', onKeydown, true)
      document.body.style.overflow = ''
      lastFocused?.focus()
      lastFocused = null
    }
  },
)

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown, true)
  document.body.style.overflow = ''
})

function cancel() {
  emit('cancel')
  emit('update:open', false)
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="confirm-overlay" @click.self="cancel">
      <div
        ref="dialogRef"
        class="confirm"
        role="dialog"
        aria-modal="true"
        :aria-label="title"
      >
        <h2 class="confirm__title">{{ title }}</h2>
        <p v-if="message" class="confirm__message">{{ message }}</p>
        <p v-if="impact" class="confirm__impact">{{ impact }}</p>
        <div class="confirm__actions">
          <button class="btn sm" @click="cancel">{{ cancelText }}</button>
          <button
            ref="confirmRef"
            class="btn sm"
            :class="tone === 'danger' ? 'ox' : 'primary'"
            @click="emit('confirm')"
          >
            {{ confirmText }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.confirm-overlay {
  position: fixed;
  inset: 0;
  z-index: 70;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(30, 24, 16, 0.34);
  backdrop-filter: blur(1.5px);
  animation: fadeIn 0.16s var(--ease);
}

.confirm {
  width: min(420px, 100%);
  background: var(--paper-raised);
  border: 1px solid var(--line);
  border-radius: var(--r-m);
  box-shadow: var(--shadow);
  padding: 18px 20px 16px;
}

.confirm__title {
  margin: 0;
  font-family: var(--serif);
  font-size: 16px;
  font-weight: 700;
}

.confirm__message {
  margin: 8px 0 0;
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--ink-soft);
}

.confirm__impact {
  margin: 10px 0 0;
  font-size: 11px;
  line-height: 1.7;
  color: var(--amber);
  background: var(--amber-soft);
  border: 1px solid var(--amber-line);
  border-radius: var(--r-s);
  padding: 7px 9px;
}

.confirm__actions {
  display: flex;
  justify-content: flex-end;
  gap: 7px;
  margin-top: 16px;
}

@keyframes fadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}
</style>
