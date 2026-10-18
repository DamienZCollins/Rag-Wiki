<script setup lang="ts">
/** 内联 SVG 图标集。原型全部用内联 SVG，这里按名字抽取，避免每处重复路径 */
export type IconName =
  | 'chat'
  | 'chart'
  | 'book'
  | 'warning'
  | 'link'
  | 'moon'
  | 'sun'
  | 'plus'
  | 'search'
  | 'filter'
  | 'copy'
  | 'check'
  | 'close'
  | 'send'
  | 'stop'
  | 'chev'
  | 'chevron-down'
  | 'info'
  | 'bolt'
  | 'bolt-off'
  | 'doc'
  | 'layers'
  | 'refresh'
  | 'trash'
  | 'download'
  | 'external'
  | 'user'
  | 'gear'
  | 'arrow-left'
  | 'arrow-right'
  | 'alert'

const props = withDefaults(
  defineProps<{
    name: IconName
    size?: number | string
    strokeWidth?: number
  }>(),
  { size: 17, strokeWidth: 1.7 },
)

const paths: Record<IconName, string[]> = {
  chat: ['M21 11.5a8.4 8.4 0 0 1-9 8.4 8.9 8.9 0 0 1-4-.9L3 21l1.9-4.8A8.4 8.4 0 0 1 12 3.1a8.4 8.4 0 0 1 9 8.4Z'],
  chart: ['M4 20V10M10 20V4M16 20v-7M22 20H2'],
  book: ['M4 4.5A2.5 2.5 0 0 1 6.5 2H20v17H6.5A2.5 2.5 0 0 0 4 21.5v-17Z', 'M4 19.5A2.5 2.5 0 0 1 6.5 17H20'],
  warning: ['M12 3 2.8 19.4A1.5 1.5 0 0 0 4.1 21h15.8a1.5 1.5 0 0 0 1.3-1.6L12 3Z', 'M12 9v5M12 17.5v.01'],
  link: ['M10 13a5 5 0 0 0 7 0l2-2a5 5 0 0 0-7-7l-1 1', 'M14 11a5 5 0 0 0-7 0l-2 2a5 5 0 0 0 7 7l1-1'],
  moon: ['M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z'],
  sun: ['M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10Z', 'M12 1v2M12 21v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M1 12h2M21 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4'],
  plus: ['M12 5v14M5 12h14'],
  search: ['M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14Z', 'm20 20-4-4'],
  filter: ['M4 6h16M7 12h10M10 18h4'],
  copy: ['M9 9h11v11H9Z', 'M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1'],
  check: ['m5 12 4 4L19 6'],
  close: ['M18 6 6 18M6 6l12 12'],
  send: ['M12 19V5M5 12l7-7 7 7'],
  stop: ['M7 7h10v10H7Z'],
  chev: ['m9 6 6 6-6 6'],
  'chevron-down': ['m6 9 6 6 6-6'],
  info: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z', 'M12 8h.01M11 12h1v4h1'],
  bolt: ['m13 2-9 12h7l-1 8 9-12h-7l1-8Z'],
  'bolt-off': ['m13 2-9 12h7l-1 8 9-12h-7l1-8Z', 'M3 3l18 18'],
  doc: ['M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z', 'M14 2v6h6M9 13h6M9 17h6'],
  layers: ['M12 2 2 7l10 5 10-5-10-5Z', 'm2 12 10 5 10-5M2 17l10 5 10-5'],
  refresh: ['M3 12a9 9 0 1 0 3-6.7L3 8', 'M3 3v5h5'],
  trash: ['M3 6h18M8 6V4h8v2M6 6l1 15h10l1-15'],
  download: ['M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3'],
  external: ['M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6', 'M15 3h6v6M10 14 21 3'],
  user: ['M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2', 'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z'],
  gear: ['M12 5.5a1.4 1.4 0 1 0 0-.01M12 12.5a1.4 1.4 0 1 0 0-.01M12 19.5a1.4 1.4 0 1 0 0-.01'],
  'arrow-left': ['M19 12H5M12 19l-7-7 7-7'],
  'arrow-right': ['M5 12h14M12 5l7 7-7 7'],
  alert: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z', 'M12 8v5M12 16.5v.01'],
}

const dimension = typeof props.size === 'number' ? `${props.size}px` : props.size
</script>

<template>
  <svg
    class="icon"
    :style="{ width: dimension, height: dimension }"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    :stroke-width="strokeWidth"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
  >
    <path v-for="(d, index) in paths[name]" :key="index" :d="d" />
  </svg>
</template>
