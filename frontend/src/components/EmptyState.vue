<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string
    description?: string
    actionText?: string
    compact?: boolean
  }>(),
  { compact: false },
)

const emit = defineEmits<{ action: [] }>()
</script>

<template>
  <div class="empty" :class="{ 'empty--compact': compact }">
    <div class="empty__title">{{ title }}</div>
    <p v-if="description" class="empty__desc">{{ description }}</p>
    <button v-if="actionText" class="btn sm" @click="emit('action')">{{ actionText }}</button>
    <slot />
  </div>
</template>

<style scoped>
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 9px;
  padding: 34px 20px;
  text-align: center;
  color: var(--ink-mute);
}

.empty--compact {
  padding: 18px 12px;
}

.empty__title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-soft);
}

.empty__desc {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.7;
  max-width: 420px;
}
</style>
