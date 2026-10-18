<script setup lang="ts">
import { computed } from 'vue'
import type { PageContent, WikiLink } from '@/api/wiki'
import WikiLinkChip from './WikiLink.vue'
import { DOC_SECTIONS } from './sections'

/**
 * 正文四段。结构固定为 摘要 / 适用条件 / 例外 / 关联条目，按 01–04 编号，
 * 与 backend/wiki/pages/<page_id>.md 的四个二级标题一一对应。
 * 空段落明确说明「无」，不留空白块。
 */

const props = defineProps<{
  content: PageContent
  relatedLinks: WikiLink[]
}>()

const emit = defineEmits<{ pending: [link: WikiLink] }>()

const hasSummary = computed(() => props.content.summary.trim().length > 0)
const hasConditions = computed(() => props.content.conditions.length > 0)
const hasExceptions = computed(() => props.content.exceptions.length > 0)
const hasLinks = computed(() => props.relatedLinks.length > 0)
</script>

<template>
  <div class="doc-sections">
    <section :id="DOC_SECTIONS[0].id" class="doc-section">
      <h2 class="h2">
        <span class="num">01</span>
        {{ DOC_SECTIONS[0].label }}
      </h2>
      <p v-if="hasSummary" class="summary-text">{{ content.summary }}</p>
      <p v-else class="muted">摘要为空。</p>
    </section>

    <section :id="DOC_SECTIONS[1].id" class="doc-section">
      <h2 class="h2">
        <span class="num">02</span>
        {{ DOC_SECTIONS[1].label }}
      </h2>
      <ul v-if="hasConditions" class="bullet-list">
        <li v-for="(item, index) in content.conditions" :key="index">{{ item }}</li>
      </ul>
      <p v-else class="muted">未给出适用条件。</p>
    </section>

    <section :id="DOC_SECTIONS[2].id" class="doc-section">
      <h2 class="h2">
        <span class="num">03</span>
        {{ DOC_SECTIONS[2].label }}
      </h2>
      <ul v-if="hasExceptions" class="bullet-list">
        <li v-for="(item, index) in content.exceptions" :key="index">{{ item }}</li>
      </ul>
      <p v-else class="muted">无例外。</p>
    </section>

    <section :id="DOC_SECTIONS[3].id" class="doc-section">
      <h2 class="h2">
        <span class="num">04</span>
        {{ DOC_SECTIONS[3].label }}
      </h2>
      <p v-if="hasLinks" class="link-row">
        <WikiLinkChip
          v-for="link in relatedLinks"
          :key="link.title"
          :link="link"
          @pending="emit('pending', $event)"
        />
      </p>
      <p v-else class="muted">没有关联条目。</p>
    </section>
  </div>
</template>

<style scoped>
.doc-sections {
  display: block;
}

.doc-section {
  margin-top: 22px;
}

.doc-section:first-child {
  margin-top: 16px;
}

.h2 {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 14px;
  font-weight: 700;
  color: var(--ink);
  margin: 0 0 8px;
}

.num {
  font-family: var(--mono);
  font-size: 9.5px;
  color: var(--ink-faint);
  font-weight: 400;
}

.summary-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.9;
  color: var(--ink-soft);
  padding-left: 11px;
  border-left: 2px solid var(--teal-line);
}

.bullet-list {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.bullet-list li {
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--ink-soft);
}

.bullet-list li::marker {
  color: var(--ink-faint);
}

.link-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 18px;
  margin: 0;
}

.muted {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--ink-faint);
}
</style>
