<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import type { WikiLink } from '@/api/wiki'
import WikiLinkChip from './WikiLink.vue'

/**
 * 反向链接。数量与 summary.inboundCount 一致（后端 listLinksTo 的长度）。
 * 空列表时明确说明「没有被引用」，不留空白卡片。
 */

defineProps<{
  links: WikiLink[]
  count: number
}>()

const emit = defineEmits<{ pending: [link: WikiLink] }>()
</script>

<template>
  <section class="side-card" aria-labelledby="backlink-head">
    <div class="side-card-head">
      <span id="backlink-head">反向链接</span>
      <span class="mono" :title="`接口 inboundCount=${count}`">
        {{ links.length }} / {{ count }}
      </span>
    </div>
    <div class="side-card-body">
      <template v-if="links.length > 0">
        <WikiLinkChip
          v-for="link in links"
          :key="link.title"
          :link="link"
          variant="row"
          @pending="emit('pending', $event)"
        />
      </template>
      <p v-else class="empty-line">
        <AppIcon name="info" :size="12" />
        没有任何条目引用它。
      </p>
    </div>
  </section>
</template>

<style scoped>
.side-card {
  background: var(--paper-raised);
  border: 1px solid var(--line);
  border-radius: 10px;
  overflow: hidden;
}

.side-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 9px 11px;
  border-bottom: 1px solid var(--line-soft);
  font-size: 11px;
  font-weight: 700;
  color: var(--ink-soft);
}

.side-card-head .mono {
  font-size: 9.5px;
  font-weight: 400;
  color: var(--ink-faint);
}

.side-card-body {
  padding: 8px 11px 9px;
  max-height: 264px;
  overflow: auto;
  scrollbar-width: thin;
}

.empty-line {
  display: flex;
  align-items: flex-start;
  gap: 5px;
  margin: 0;
  font-size: 11px;
  line-height: 1.7;
  color: var(--ink-faint);
}

.empty-line :deep(.icon) {
  width: 12px;
  height: 12px;
  margin-top: 3px;
  flex: none;
}
</style>
