<script setup lang="ts">
import { computed } from 'vue'

/** 条目状态 DRAFT/ACTIVE/RETIRED、快通道状态 CANDIDATE/ACTIVE/OFFLINE 统一配色映射 */
type BadgeStatus =
  | 'DRAFT'
  | 'ACTIVE'
  | 'RETIRED'
  | 'CANDIDATE'
  | 'OFFLINE'
  | 'PENDING'
  | 'FASTPATH'
  | 'RAG'
  | 'MAX_STEPS'
  | 'FAILED'
  | 'CANCELLED'
  | 'COMPLETED'

const props = defineProps<{ status: BadgeStatus | string | null | undefined }>()

const labelMap: Record<string, string> = {
  DRAFT: '草稿',
  ACTIVE: '生效',
  RETIRED: '已失效',
  CANDIDATE: '候选',
  OFFLINE: '已下线',
  PENDING: '待创建',
  FASTPATH: '快通道',
  RAG: 'RAG',
  MAX_STEPS: '已转人工',
  FAILED: '失败',
  CANCELLED: '已中断',
  COMPLETED: '已完成',
}

const toneMap: Record<string, string> = {
  DRAFT: 'amber',
  ACTIVE: 'green',
  RETIRED: 'plain',
  CANDIDATE: 'amber',
  OFFLINE: 'plain',
  PENDING: 'amber',
  FASTPATH: 'teal',
  RAG: 'blue',
  MAX_STEPS: 'amber',
  FAILED: 'ox',
  CANCELLED: 'plain',
  COMPLETED: 'green',
}

const text = computed(() => {
  const key = (props.status ?? '').toUpperCase()
  return labelMap[key] ?? (props.status ? String(props.status) : '—')
})

const tone = computed(() => {
  const key = (props.status ?? '').toUpperCase()
  return toneMap[key] ?? ''
})
</script>

<template>
  <span class="tag" :class="tone">
    {{ text }}
  </span>
</template>
