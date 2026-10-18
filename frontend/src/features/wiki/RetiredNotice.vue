<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import type { WikiLink, WikiPageSummary } from '@/api/wiki'
import WikiLinkChip from './WikiLink.vue'
import { formatDate } from './format'

/**
 * 已失效条目顶部提示。
 *
 * 后端详情接口不返回 supersededBy，所以这里不声称「被某条目取代」：
 * 只陈述失效事实，再列出关联里确实处于生效状态的条目供继续阅读。
 */

const props = defineProps<{
  summary: WikiPageSummary
  activeLinks: WikiLink[]
}>()

const emit = defineEmits<{ pending: [link: WikiLink] }>()

const periodText = computed(() => {
  const start = props.summary.effectiveAt ? formatDate(props.summary.effectiveAt) : '未标注'
  const end = props.summary.expiresAt ? formatDate(props.summary.expiresAt) : '未标注失效日期'
  return `${start} 起，${end}`
})
</script>

<template>
  <div class="deprecated" role="note">
    <AppIcon name="warning" :size="14" />
    <div class="deprecated__body">
      <p class="deprecated__title">本条已失效，不再用于客服回答</p>
      <p class="deprecated__text">
        状态为「已失效」，生效期 {{ periodText }}。历史工单仍可据此追溯当时的判定依据。
      </p>
      <p v-if="activeLinks.length > 0" class="deprecated__text">
        以下为当前生效的关联条目：
        <template v-for="(link, index) in activeLinks" :key="link.title">
          <span v-if="index > 0" class="deprecated__sep">·</span>
          <WikiLinkChip :link="link" @pending="emit('pending', $event)" />
        </template>
      </p>
      <p v-else class="deprecated__text">
        关联条目里没有处于生效状态的条目，无法给出当前替代口径，请以最新编译结果为准。
      </p>
    </div>
  </div>
</template>

<style scoped>
.deprecated {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  background: var(--oxblood-soft);
  border: 1px solid var(--oxblood-line);
  border-radius: 8px;
  padding: 9px 11px;
  font-size: 11.5px;
  color: var(--oxblood);
  margin-top: 14px;
}

.deprecated :deep(.icon) {
  width: 14px;
  height: 14px;
  margin-top: 2px;
  flex: none;
}

.deprecated__body {
  min-width: 0;
}

.deprecated__title {
  margin: 0 0 3px;
  font-weight: 700;
  font-size: 12px;
}

.deprecated__text {
  margin: 0;
  line-height: 1.7;
  color: var(--oxblood);
}

.deprecated__text + .deprecated__text {
  margin-top: 3px;
}

.deprecated__sep {
  margin: 0 5px;
  opacity: 0.6;
}

.deprecated :deep(.link-chip) {
  color: var(--oxblood);
  border-bottom-color: var(--oxblood-line);
  font-size: 11.5px;
}

.deprecated :deep(.link-chip:hover) {
  border-bottom-color: var(--oxblood);
}
</style>
