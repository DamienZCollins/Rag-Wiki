<script setup lang="ts">
import { computed } from 'vue'
import { LAYER_HINT, LAYER_TEXT, LAYER_TONE, type LayerKey } from './layers'

/** 归因层徽章。颜色是语义色，但文字始终在位：色觉障碍下也能读出是哪一层 */
const props = withDefaults(
  defineProps<{
    layer: LayerKey
    /** 是否附带该层判定口径的 title 提示 */
    hint?: boolean
    compact?: boolean
  }>(),
  { hint: true, compact: false },
)

const text = computed(() => LAYER_TEXT[props.layer] ?? props.layer)
const tone = computed(() => LAYER_TONE[props.layer] ?? 'none')
const title = computed(() => (props.hint ? `${text.value}：${LAYER_HINT[props.layer]}` : undefined))
</script>

<template>
  <span class="attr-badge" :class="[tone, { compact }]" :title="title">{{ text }}</span>
</template>

<style scoped>
.attr-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 10px;
  line-height: 18px;
  padding: 0 6px;
  border-radius: 4px;
  white-space: nowrap;
  font-weight: 600;
}

.attr-badge.compact {
  font-size: 9.5px;
  line-height: 16px;
  padding: 0 5px;
}

.attr-badge.compile {
  background: var(--green-soft);
  color: var(--green);
  border: 1px solid var(--green-line);
}

.attr-badge.recall {
  background: var(--amber-soft);
  color: var(--amber);
  border: 1px solid var(--amber-line);
}

.attr-badge.rank {
  background: var(--blue-soft);
  color: var(--blue);
  border: 1px solid var(--blue-line);
}

.attr-badge.gen {
  background: var(--oxblood-soft);
  color: var(--oxblood);
  border: 1px solid var(--oxblood-line);
}

.attr-badge.fallback {
  background: var(--paper);
  color: var(--ink-mute);
  border: 1px solid var(--line-strong);
}

.attr-badge.none {
  background: var(--paper-deep);
  color: var(--ink-faint);
  border: 1px dashed var(--line-strong);
}
</style>
