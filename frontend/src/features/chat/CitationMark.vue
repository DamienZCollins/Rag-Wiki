<script setup lang="ts">
import { computed } from 'vue'

/**
 * 正文里的 [n] 角标。
 * 越界编号（本轮检索里没有这个号）标为无效引用：仍然可聚焦、可点击，但明确写「无效」且不跳转。
 */

const props = defineProps<{
  index: number
  /** 本轮是否存在这个编号的片段 */
  valid: boolean
}>()

const emit = defineEmits<{ open: [event: MouseEvent] }>()

const label = computed(() =>
  props.valid ? `引用 ${props.index}，打开来源详情` : `引用 ${props.index} 无效：本轮检索没有这一条`,
)
</script>

<template>
  <button
    type="button"
    class="cite"
    :class="{ 'cite--invalid': !valid }"
    data-testid="citation-mark"
    :aria-label="label"
    :title="label"
    @click="emit('open', $event)"
  >
    <span aria-hidden="true">{{ index }}</span>
    <span v-if="!valid" class="cite__flag" aria-hidden="true">无效</span>
  </button>
</template>

<style scoped>
.cite {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-family: var(--mono);
  font-size: 10px;
  font-weight: 600;
  line-height: 16px;
  color: var(--teal);
  background: var(--teal-soft);
  border: 1px solid var(--teal-line);
  padding: 0 4px;
  border-radius: 4px;
  margin: 0 1px;
  vertical-align: 1px;
  transition:
    background 0.15s,
    color 0.15s,
    border-color 0.15s;
}

.cite:hover {
  background: var(--teal);
  color: var(--paper-raised);
}

.cite--invalid {
  color: var(--ink-mute);
  background: var(--paper-sunken);
  border-color: var(--line-strong);
  border-style: dashed;
  text-decoration: line-through;
}

.cite--invalid:hover {
  background: var(--paper-deep);
  color: var(--ink-soft);
}

.cite__flag {
  font-family: var(--sans);
  font-size: 9px;
  font-weight: 600;
  text-decoration: none;
}
</style>
