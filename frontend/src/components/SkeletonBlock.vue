<script setup lang="ts">
withDefaults(
  defineProps<{
    width?: string
    height?: string
    radius?: string
    /** 行数，多行骨架时按行铺开 */
    lines?: number
  }>(),
  { width: '100%', height: '14px', radius: '6px', lines: 1 },
)
</script>

<template>
  <div class="skeleton-group" :aria-busy="true" aria-hidden="true">
    <div
      v-for="line in lines"
      :key="line"
      class="skeleton"
      :style="{
        width: lines > 1 ? (line === lines ? '62%' : width) : width,
        height,
        borderRadius: radius,
      }"
    />
  </div>
</template>

<style scoped>
.skeleton-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.skeleton {
  background: var(--paper-deep);
  animation: shimmer 1.4s ease-in-out infinite;
}

@keyframes shimmer {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.55;
  }
}

@media (prefers-reduced-motion: reduce) {
  .skeleton {
    animation: none;
  }
}
</style>
