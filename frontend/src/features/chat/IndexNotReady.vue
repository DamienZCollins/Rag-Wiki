<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'

/**
 * 索引未就绪（4001 / HTTP 503）。
 * 与拒答是两回事：这里是索引里没有可召回文档，不是分数不够，所以文案、配色、动作都不同。
 */

defineProps<{ detail?: string | null }>()

const emit = defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="index-not-ready" role="alert">
    <div class="top">
      <AppIcon name="layers" :size="18" class="icon-box" />
      <div>
        <div class="title">索引未就绪，检索没有可召回的文档</div>
        <p class="text">
          这不是拒答：知识库里还没有可检索的片段，两路召回都是空的。
          需要先到运维台重建索引，重建完成后重新提问。
        </p>
        <p v-if="detail" class="detail mono">{{ detail }}</p>
      </div>
    </div>
    <div class="actions">
      <RouterLink class="btn sm primary" to="/ops">
        <AppIcon name="layers" :size="14" />
        去运维台重建索引
      </RouterLink>
      <button class="btn sm" type="button" @click="emit('retry')">重建后重试</button>
    </div>
  </div>
</template>

<style scoped>
.index-not-ready {
  border: 1px solid var(--blue-line);
  background: var(--blue-soft);
  border-radius: 10px;
  padding: 13px 15px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.top {
  display: flex;
  gap: 9px;
  align-items: flex-start;
}

.icon-box {
  color: var(--blue);
  margin-top: 2px;
  flex: none;
}

.title {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink);
}

.text {
  margin: 3px 0 0;
  font-size: 12px;
  line-height: 1.75;
  color: var(--ink-soft);
}

.detail {
  margin: 5px 0 0;
  font-size: 10.5px;
  color: var(--blue);
  word-break: break-all;
}

.actions {
  display: flex;
  gap: 7px;
  padding-left: 27px;
  flex-wrap: wrap;
}
</style>
