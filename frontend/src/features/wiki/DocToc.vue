<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { DocSection } from './sections'

/**
 * 本页目录。正文四段固定渲染，目录相应固定列出四节；
 * 点击滚到对应章节，滚动时反查当前可见章节并高亮。
 */

const props = defineProps<{
  sections: DocSection[]
  /** 滚动容器，用于计算可见章节；不传则退化为窗口滚动 */
  scroller?: HTMLElement | null
}>()

const activeId = ref(props.sections[0]?.id ?? '')
let frame = 0
let boundTarget: HTMLElement | Window | null = null

function updateActive() {
  const root = props.scroller
  const offset = 96
  let current = props.sections[0]?.id ?? ''

  for (const section of props.sections) {
    const element = document.getElementById(section.id)
    if (!element) continue
    const top = root
      ? element.getBoundingClientRect().top - root.getBoundingClientRect().top
      : element.getBoundingClientRect().top
    if (top <= offset) current = section.id
  }

  activeId.value = current
}

function onScroll() {
  if (frame) return
  frame = requestAnimationFrame(() => {
    frame = 0
    updateActive()
  })
}

/** 容器 ref 在父组件挂载后才就绪，所以绑定要跟着 scroller 变化走 */
function bindTarget() {
  const next = props.scroller ?? window
  if (next === boundTarget) return
  boundTarget?.removeEventListener('scroll', onScroll)
  boundTarget = next
  boundTarget.addEventListener('scroll', onScroll, { passive: true })
  updateActive()
}

watch(() => props.scroller, bindTarget, { immediate: true })
watch(
  () => props.sections,
  () => {
    activeId.value = props.sections[0]?.id ?? ''
  },
)

onBeforeUnmount(() => {
  boundTarget?.removeEventListener('scroll', onScroll)
  boundTarget = null
  if (frame) cancelAnimationFrame(frame)
})

function scrollTo(section: DocSection, event: MouseEvent) {
  const element = document.getElementById(section.id)
  const container = props.scroller
  if (!element) return
  event.preventDefault()

  if (container) {
    // 章节标题贴到容器顶部留一点间距，避免被裁掉
    const top =
      element.getBoundingClientRect().top -
      container.getBoundingClientRect().top +
      container.scrollTop -
      12
    container.scrollTo({ top: Math.max(0, top), behavior: 'smooth' })
  } else {
    element.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  activeId.value = section.id
  // 地址栏保留章节锚点，刷新后可回到同一位置
  history.replaceState(null, '', `#${section.id}`)
}

/** 容器 ref 由父组件在挂载后赋值，绑定逻辑见 bindTarget */
const countLabel = computed(() => `${props.sections.length} 节`)
</script>

<template>
  <section class="side-card" aria-labelledby="toc-head">
    <div class="side-card-head">
      <span id="toc-head">本页目录</span>
      <span class="mono">{{ countLabel }}</span>
    </div>
    <div class="side-card-body">
      <ul class="toc">
        <li v-for="section in sections" :key="section.id">
          <a
            :href="`#${section.id}`"
            :class="{ active: activeId === section.id }"
            :aria-current="activeId === section.id ? 'location' : undefined"
            @click="scrollTo(section, $event)"
          >
            <span class="toc-num mono">{{ String(section.index).padStart(2, '0') }}</span>
            {{ section.label }}
          </a>
        </li>
      </ul>
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
}

.toc {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.toc a {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--ink-mute);
  text-decoration: none;
  padding: 4px 6px;
  border-radius: 4px;
}

.toc a:hover {
  background: var(--paper);
  color: var(--ink);
}

.toc a.active {
  color: var(--oxblood);
  background: var(--oxblood-soft);
  font-weight: 600;
}

.toc-num {
  font-size: 9px;
  color: var(--ink-faint);
}

.toc a.active .toc-num {
  color: var(--oxblood);
}
</style>
