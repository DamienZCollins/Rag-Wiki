<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import type { OpsFailure } from './types'

/** 失败提示：后端原始 message 与业务码原样展示，不做二次改写 */
defineProps<{
  failure: OpsFailure
  title?: string
}>()

const emit = defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="ops-failure" role="alert">
    <AppIcon name="alert" :size="16" class="ops-failure__icon" />
    <div class="ops-failure__body">
      <div class="ops-failure__title">{{ title ?? '操作失败' }}</div>
      <p class="ops-failure__message">{{ failure.message }}</p>
      <p class="ops-failure__code mono">
        code = {{ failure.code ?? '—' }}
        <template v-if="failure.httpStatus"> · HTTP {{ failure.httpStatus }}</template>
        <template v-if="failure.kind"> · {{ failure.kind }}</template>
      </p>
      <button class="btn sm" @click="emit('retry')">重试</button>
    </div>
  </div>
</template>

<style scoped>
.ops-failure {
  display: flex;
  gap: 9px;
  align-items: flex-start;
  padding: 11px 12px;
  border: 1px solid var(--oxblood-line);
  background: var(--oxblood-soft);
  border-radius: var(--r-s);
}

.ops-failure__icon {
  color: var(--oxblood);
  margin-top: 2px;
  flex: none;
}

.ops-failure__body {
  min-width: 0;
}

.ops-failure__title {
  font-size: 12px;
  font-weight: 700;
  color: var(--oxblood);
}

.ops-failure__message {
  margin: 3px 0 0;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-soft);
  overflow-wrap: anywhere;
}

.ops-failure__code {
  margin: 3px 0 8px;
  font-size: 10.5px;
  color: var(--ink-mute);
}
</style>
