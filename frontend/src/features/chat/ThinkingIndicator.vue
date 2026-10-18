<script setup lang="ts">
/**
 * 思考态。推理模型的 reasoning_content 不推给前端，THINKING 之后有 1~2 秒静默，
 * 这里是那段静默期唯一的可见反馈，必须显式可见而不是靠颜色。
 */
withDefaults(defineProps<{ label?: string }>(), { label: '正在思考' })
</script>

<template>
  <div class="thinking" data-testid="thinking-indicator" role="status" aria-live="polite">
    <span class="dots" aria-hidden="true"><i></i><i></i><i></i></span>
    <span class="thinking-text">{{ label }}</span>
    <span class="thinking-hint">模型正在推理，首个分片到达前不会有正文</span>
  </div>
</template>

<style scoped>
.thinking {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: 1px dashed var(--line-strong);
  border-radius: 9px;
  background: var(--paper);
  color: var(--ink-mute);
  font-size: 11.5px;
}

.dots {
  display: inline-flex;
  gap: 3px;
}

.dots i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--ink-faint);
  animation: blink 1.2s infinite ease-in-out;
}

.dots i:nth-child(2) {
  animation-delay: 0.18s;
}

.dots i:nth-child(3) {
  animation-delay: 0.36s;
}

.thinking-text {
  font-weight: 600;
  color: var(--ink-soft);
}

.thinking-hint {
  font-size: 10.5px;
  color: var(--ink-faint);
}

@keyframes blink {
  0%,
  100% {
    opacity: 0.35;
    transform: translateY(0);
  }
  50% {
    opacity: 1;
    transform: translateY(-1px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .dots i {
    animation: none;
    opacity: 0.7;
  }
}
</style>
