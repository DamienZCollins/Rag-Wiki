<script setup lang="ts">
import { computed } from 'vue'
import CitationMark from './CitationMark.vue'
import { findCitation, parseBlocks, type BlockNode } from './citations'
import type { CitationList } from './types'

/**
 * 助手正文渲染：段落、有序/无序列表、**加粗**、[n] 引用角标。
 * 不引 markdown 库：后端输出只用到这几种结构，自己解析能保证角标不被当成纯文本。
 */

const props = defineProps<{
  text: string
  citationLists: CitationList[]
}>()

const emit = defineEmits<{ 'open-citation': [index: number, event: MouseEvent] }>()

const blocks = computed<BlockNode[]>(() => parseBlocks(props.text))

function isValid(index: number): boolean {
  return findCitation(props.citationLists, index) !== null
}
</script>

<template>
  <div class="rich">
    <template v-for="(block, bi) in blocks" :key="bi">
      <ul v-if="block.type === 'ul'" class="rich-list">
        <li v-for="(item, ii) in block.items" :key="ii">
          <template v-for="(node, ni) in item" :key="ni">
            <strong v-if="node.type === 'strong'">{{ node.text }}</strong>
            <CitationMark
              v-else-if="node.type === 'cite'"
              :index="node.index"
              :valid="isValid(node.index)"
              @open="emit('open-citation', node.index, $event)"
            />
            <template v-else>{{ node.text }}</template>
          </template>
        </li>
      </ul>
      <ol v-else-if="block.type === 'ol'" class="rich-list">
        <li v-for="(item, ii) in block.items" :key="ii">
          <template v-for="(node, ni) in item" :key="ni">
            <strong v-if="node.type === 'strong'">{{ node.text }}</strong>
            <CitationMark
              v-else-if="node.type === 'cite'"
              :index="node.index"
              :valid="isValid(node.index)"
              @open="emit('open-citation', node.index, $event)"
            />
            <template v-else>{{ node.text }}</template>
          </template>
        </li>
      </ol>
      <p v-else>
        <template v-for="(node, ni) in block.inlines" :key="ni">
          <strong v-if="node.type === 'strong'">{{ node.text }}</strong>
          <CitationMark
            v-else-if="node.type === 'cite'"
            :index="node.index"
            :valid="isValid(node.index)"
            @open="emit('open-citation', node.index, $event)"
          />
          <template v-else>{{ node.text }}</template>
        </template>
      </p>
    </template>
  </div>
</template>

<style scoped>
.rich p {
  margin: 0 0 9px;
  white-space: pre-wrap;
  word-break: break-word;
}

.rich p:last-child {
  margin-bottom: 0;
}

.rich-list {
  margin: 0 0 9px;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.rich-list:last-child {
  margin-bottom: 0;
}

.rich-list li {
  word-break: break-word;
}

.rich-list li::marker {
  color: var(--ink-faint);
}

.rich strong {
  font-weight: 700;
}
</style>
