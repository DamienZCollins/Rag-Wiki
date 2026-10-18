<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import type { WikiLink } from '@/api/wiki'

/**
 * 一条双链。
 * ACTIVE 渲染成可跳转的 RouterLink；PENDING（目标条目还不存在）渲染成按钮，
 * 点击只给提示，不进空白页。
 */

const props = defineProps<{
  link: WikiLink
  /** chip 用于正文与关联条目，row 用于右栏列表 */
  variant?: 'chip' | 'row'
}>()

const emit = defineEmits<{ pending: [link: WikiLink] }>()

const isActive = computed(() => props.link.status === 'ACTIVE' && Boolean(props.link.pageId))
</script>

<template>
  <RouterLink
    v-if="isActive"
    class="link-chip"
    :class="`link-chip--${variant ?? 'chip'}`"
    :to="{ name: 'wiki-page', params: { pageId: link.pageId as string } }"
  >
    <AppIcon v-if="variant === 'row'" name="link" :size="13" />
    <span class="link-text">{{ link.title }}</span>
  </RouterLink>

  <button
    v-else
    type="button"
    class="link-chip link-chip--pending"
    :class="`link-chip--${variant ?? 'chip'}`"
    :title="`「${link.title}」还没有对应条目`"
    @click="emit('pending', link)"
  >
    <AppIcon v-if="variant === 'row'" name="link" :size="13" />
    <span class="link-text">{{ link.title }}</span>
    <span class="pending-mark">待创建</span>
  </button>
</template>

<style scoped>
.link-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--teal);
  text-decoration: none;
  border-bottom: 1px solid var(--teal-line);
  font-size: 12.5px;
  line-height: 1.7;
  text-align: left;
  background: none;
  padding: 0;
}

.link-chip:hover {
  border-bottom-color: var(--teal);
}

.link-chip--pending {
  color: var(--ink-faint);
  border-bottom-style: dashed;
  border-bottom-color: var(--line-strong);
  cursor: not-allowed;
}

.link-chip--pending:hover {
  border-bottom-color: var(--ink-mute);
  color: var(--ink-mute);
}

.pending-mark {
  font-size: 9.5px;
  line-height: 15px;
  padding: 0 4px;
  border-radius: 4px;
  border: 1px dashed var(--line-strong);
  color: var(--ink-faint);
  white-space: nowrap;
}

.link-chip--row {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  width: 100%;
  padding: 6px 0;
  border-bottom: 1px solid var(--line-soft);
  font-size: 11.5px;
  color: var(--ink-soft);
}

.link-chip--row:hover {
  border-bottom-color: var(--line-soft);
  color: var(--oxblood);
}

.link-chip--row :deep(.icon) {
  width: 13px;
  height: 13px;
  color: var(--ink-faint);
  margin-top: 3px;
}

.link-chip--row:hover :deep(.icon) {
  color: var(--oxblood);
}

.link-text {
  min-width: 0;
  word-break: break-word;
}
</style>
