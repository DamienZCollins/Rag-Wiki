<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'
import { useToast } from '@/composables/useToast'

const { items, dismiss } = useToast()

const iconOf = (kind: string) => {
  if (kind === 'error') return 'alert' as const
  if (kind === 'info') return 'info' as const
  return 'check' as const
}

const ariaLive = computed(() => (items.value.length > 0 ? 'polite' : 'off'))
</script>

<template>
  <div class="toast-host" role="status" :aria-live="ariaLive">
    <div
      v-for="item in items"
      :key="item.id"
      class="toast"
      :class="`toast--${item.kind}`"
    >
      <AppIcon :name="iconOf(item.kind)" :size="14" />
      <span class="toast__text">{{ item.message }}</span>
      <button class="toast__close" aria-label="关闭提示" @click="dismiss(item.id)">
        <AppIcon name="close" :size="12" />
      </button>
    </div>
  </div>
</template>

<style scoped>
.toast-host {
  position: fixed;
  left: 50%;
  bottom: 22px;
  transform: translateX(-50%);
  z-index: 60;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  pointer-events: none;
  max-width: 90vw;
}

.toast {
  display: flex;
  align-items: center;
  gap: 7px;
  background: var(--ink);
  color: var(--paper-raised);
  font-size: 12px;
  padding: 8px 10px 8px 13px;
  border-radius: 8px;
  box-shadow: 0 14px 36px -16px rgba(0, 0, 0, 0.5);
  animation: toastIn 0.2s var(--ease);
  pointer-events: auto;
  max-width: 90vw;
}

.toast--error {
  background: var(--oxblood);
  color: var(--paper-raised);
}

.toast--info {
  background: var(--ink-soft);
  color: var(--paper-raised);
}

.toast :deep(.icon) {
  color: var(--teal-line);
  flex: none;
}

.toast--error :deep(.icon) {
  color: var(--paper-raised);
}

.toast__text {
  line-height: 1.5;
}

.toast__close {
  color: inherit;
  opacity: 0.6;
  display: grid;
  place-items: center;
  padding: 2px;
  border-radius: 4px;
}

.toast__close:hover {
  opacity: 1;
}

@keyframes toastIn {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
</style>
