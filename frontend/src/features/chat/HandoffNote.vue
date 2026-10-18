<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'

/**
 * 转人工提示。
 * 后端没有工单接口，这里只把本地会话标为已转人工，不伪造工单号、坐席姓名与预计等待时间。
 */

defineProps<{
  done: boolean
  /** 已转人工时说明触发原因 */
  reason?: 'refusal' | 'max-steps' | 'manual'
}>()

const emit = defineEmits<{ handoff: [] }>()

const reasonText = {
  refusal: '检索没有可引用依据',
  'max-steps': '工具调用步数达到上限',
  manual: '用户主动要求',
} as const
</script>

<template>
  <div class="handoff" :class="{ 'handoff--done': done }">
    <AppIcon :name="done ? 'check' : 'user'" :size="16" class="handoff-icon" />
    <div class="handoff-body">
      <template v-if="done">
        <div class="handoff-title">已记录为转人工</div>
        <p class="handoff-text">
          本会话已标记为「已转人工」（触发原因：{{ reasonText[reason ?? 'manual'] }}）。
          后端暂无工单接口，这里不会生成工单号，也不会显示坐席与等待时间。
        </p>
      </template>
      <template v-else>
        <div class="handoff-title">建议转人工处理</div>
        <p class="handoff-text">{{ reasonText[reason ?? 'manual'] }}，建议转人工核实客户情况。</p>
        <button class="btn sm" type="button" @click="emit('handoff')">
          <AppIcon name="user" :size="14" />
          标记为转人工
        </button>
      </template>
    </div>
  </div>
</template>

<style scoped>
.handoff {
  display: flex;
  gap: 9px;
  align-items: flex-start;
  border: 1px dashed var(--line-strong);
  border-radius: 9px;
  background: var(--paper);
  padding: 10px 12px;
}

.handoff--done {
  border-style: solid;
  border-color: var(--green-line);
  background: var(--green-soft);
}

.handoff-icon {
  margin-top: 2px;
  color: var(--ink-mute);
  flex: none;
}

.handoff--done .handoff-icon {
  color: var(--green);
}

.handoff-body {
  min-width: 0;
}

.handoff-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-soft);
}

.handoff--done .handoff-title {
  color: var(--green);
}

.handoff-text {
  margin: 3px 0 8px;
  font-size: 11.5px;
  line-height: 1.75;
  color: var(--ink-mute);
}

.handoff--done .handoff-text {
  margin-bottom: 0;
  color: var(--ink-soft);
}
</style>
