<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import { formatScore, REFUSE_THRESHOLD } from './citations'
import type { CitationRef } from './types'

/**
 * 引用抽屉。数据全部来自本轮已取回的片段，打开时不发请求。
 * 分数来自「用同一问题调检索接口复算」，与片段本身来自工具返回，两处来源在界面上分开标注。
 */

const props = defineProps<{
  open: boolean
  citation: CitationRef | null
  index: number
  /** 编号越界：本轮没有这个片段 */
  invalid: boolean
  /** 本轮检索到的最大编号，越界时用来说明可用范围 */
  maxIndex: number
  /** 复算分数失败时的说明，为空表示分数可用 */
  scoreNote: string | null
}>()

const emit = defineEmits<{ close: [] }>()

const panelRef = ref<HTMLElement | null>(null)
const closeRef = ref<HTMLButtonElement | null>(null)
let lastFocused: HTMLElement | null = null

const FOCUSABLE =
  'button:not(:disabled), [href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])'

function focusables(): HTMLElement[] {
  if (!panelRef.value) return []
  return Array.from(panelRef.value.querySelectorAll<HTMLElement>(FOCUSABLE))
}

function onKeydown(event: KeyboardEvent) {
  if (!props.open) return
  if (event.key === 'Escape') {
    event.preventDefault()
    emit('close')
    return
  }
  if (event.key !== 'Tab') return
  // 焦点陷阱：Tab 不逃逸出抽屉
  const list = focusables()
  if (list.length === 0) {
    event.preventDefault()
    return
  }
  const first = list[0]
  const last = list[list.length - 1]
  const active = document.activeElement as HTMLElement | null
  if (event.shiftKey) {
    if (active === first || !panelRef.value?.contains(active)) {
      event.preventDefault()
      last.focus()
    }
  } else if (active === last || !panelRef.value?.contains(active)) {
    event.preventDefault()
    first.focus()
  }
}

watch(
  () => props.open,
  async (open) => {
    if (open) {
      lastFocused = document.activeElement as HTMLElement | null
      document.addEventListener('keydown', onKeydown, true)
      document.body.style.overflow = 'hidden'
      await nextTick()
      closeRef.value?.focus()
    } else {
      document.removeEventListener('keydown', onKeydown, true)
      document.body.style.overflow = ''
      // 由父组件决定把焦点还给触发角标，这里只兜底
      if (!lastFocused?.isConnected) lastFocused = null
      lastFocused = null
    }
  },
)

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown, true)
  document.body.style.overflow = ''
})

const hasScores = computed(() => Boolean(props.citation?.enriched))
const bm25Pass = computed(() => (props.citation?.bm25Score ?? 0) >= REFUSE_THRESHOLD.bm25)
const vectorPass = computed(() => (props.citation?.vectorScore ?? 0) >= REFUSE_THRESHOLD.vector)
const hasPage = computed(() => Boolean(props.citation?.pageId))

const title = computed(() => props.citation?.title ?? '无效引用')
const subtitle = computed(() => {
  if (!props.citation) return '本轮检索结果里没有这个编号'
  const parts: string[] = []
  if (props.citation.version) parts.push(props.citation.version)
  if (props.citation.headingPath) parts.push(props.citation.headingPath)
  return parts.join(' · ') || '未标注版本与章节'
})
</script>

<template>
  <Teleport to="body">
    <Transition name="drawer-fade">
      <div v-if="open" class="overlay" @click.self="emit('close')" />
    </Transition>
    <aside
      ref="panelRef"
      class="drawer"
      :class="{ 'is-open': open }"
      data-testid="citation-drawer"
      role="dialog"
      aria-modal="true"
      :aria-label="`引用 ${index} 详情`"
      :aria-hidden="!open"
      :inert="!open ? true : undefined"
    >
      <div class="drawer-head">
        <span class="n">{{ index }}</span>
        <div class="drawer-head-text">
          <div class="drawer-title">{{ title }}</div>
          <div class="drawer-sub">
            <span>{{ invalid ? '越界引用' : '检索片段' }}</span>
            <span class="dot-sep"></span>
            <span class="mono">{{ subtitle }}</span>
          </div>
        </div>
        <button ref="closeRef" class="icon-btn" type="button" aria-label="关闭引用详情" @click="emit('close')">
          <AppIcon name="close" :size="16" />
        </button>
      </div>

      <div class="drawer-body">
        <template v-if="invalid">
          <div class="warn-box" role="alert">
            <AppIcon name="alert" :size="16" />
            <div>
              <div class="warn-title">这个引用编号在本轮检索结果里不存在</div>
              <p class="warn-text">
                本轮检索最多只到编号 {{ maxIndex || '—' }}，引用 [{{ index }}] 没有对应片段。
                编号越界说明模型写了没有依据的引用，该处结论不能追溯到具体片段，请勿据此采信。
              </p>
            </div>
          </div>
        </template>

        <template v-else-if="citation">
          <div class="drawer-section-title">引用原文</div>
          <blockquote class="quote">{{ citation.content || '片段正文为空' }}</blockquote>

          <div class="drawer-section-title">溯源信息</div>
          <div class="meta-grid">
            <div>
              <div class="k">片段标识 chunkId</div>
              <div class="v mono">{{ citation.chunkId || '—' }}</div>
            </div>
            <div>
              <div class="k">来源文档 docId</div>
              <div class="v mono">{{ citation.docId || '—' }}</div>
            </div>
            <div>
              <div class="k">Wiki 条目 pageId</div>
              <div class="v mono">{{ citation.pageId || '无（索引源为原始文档）' }}</div>
            </div>
            <div>
              <div class="k">章节路径</div>
              <div class="v">{{ citation.headingPath || '—' }}</div>
            </div>
            <div class="span-2">
              <div class="k">全部来源文档 sourceDocs</div>
              <div class="v mono">
                {{ citation.sourceDocs.length > 0 ? citation.sourceDocs.join(' / ') : '—' }}
              </div>
            </div>
          </div>

          <div class="drawer-section-title">召回依据</div>
          <p class="provenance">
            片段来自本轮 <code class="mono">search_knowledge</code> 工具返回；
            两路原始分与 RRF 分来自检索接口用同一问题复算。
          </p>

          <template v-if="hasScores">
            <div class="kv-row">
              <span class="k">BM25 原始分 / 名次</span>
              <span class="v mono">
                {{ formatScore(citation.bm25Score, 3) }} / #{{ citation.bm25Rank ?? '—' }}
                <span class="tag" :class="bm25Pass ? 'green' : 'plain'">
                  {{ bm25Pass ? '高于阈值' : '低于阈值' }}
                </span>
              </span>
            </div>
            <div class="kv-row">
              <span class="k">向量相似度 / 名次</span>
              <span class="v mono">
                {{ formatScore(citation.vectorScore, 3) }} / #{{ citation.vectorRank ?? '—' }}
                <span class="tag" :class="vectorPass ? 'green' : 'plain'">
                  {{ vectorPass ? '高于阈值' : '低于阈值' }}
                </span>
              </span>
            </div>
            <div class="kv-row">
              <span class="k">RRF 融合分</span>
              <span class="v mono">{{ formatScore(citation.rrfScore, 5) }}</span>
            </div>
            <p class="threshold-note">
              拒答判定看单路原始分：BM25 ≥ {{ REFUSE_THRESHOLD.bm25 }} 或向量 ≥
              {{ REFUSE_THRESHOLD.vector }} 任意一路达标即不拒答。
            </p>
          </template>
          <p v-else class="provenance provenance--warn">
            {{ scoreNote || '两路原始分未取到：检索接口复算未成功，这里只展示片段元信息，不展示分数。' }}
          </p>
        </template>
      </div>

      <div class="drawer-foot">
        <RouterLink v-if="hasPage" class="btn sm" :to="`/wiki/${encodeURIComponent(citation!.pageId!)}`">
          <AppIcon name="book" :size="14" />
          在 Wiki 中打开
        </RouterLink>
        <span v-else-if="citation" class="foot-note">该片段没有对应 Wiki 条目</span>
        <span class="mono foot-note">{{ invalid ? '无效引用' : '只读引用' }}</span>
      </div>
    </aside>
  </Teleport>
</template>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  background: color-mix(in srgb, var(--ink) 30%, transparent);
  backdrop-filter: blur(1.5px);
  z-index: 40;
}

.drawer {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(440px, 92vw);
  background: var(--paper-raised);
  border-left: 1px solid var(--line);
  box-shadow: var(--shadow);
  transform: translateX(102%);
  transition: transform 0.28s var(--ease);
  z-index: 50;
  display: flex;
  flex-direction: column;
}

.drawer.is-open {
  transform: none;
}

.drawer-head {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 15px 16px;
  border-bottom: 1px solid var(--line);
}

.drawer-head .n {
  font-family: var(--mono);
  font-size: 10.5px;
  color: var(--teal);
  background: var(--teal-soft);
  border: 1px solid var(--teal-line);
  border-radius: 5px;
  min-width: 23px;
  height: 21px;
  display: grid;
  place-items: center;
  margin-top: 2px;
}

.drawer-head-text {
  min-width: 0;
}

.drawer-title {
  font-size: 14px;
  font-weight: 700;
  line-height: 1.4;
  padding-right: 6px;
}

.drawer-sub {
  font-size: 10.5px;
  color: var(--ink-mute);
  margin-top: 2px;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  align-items: center;
}

.drawer-head .icon-btn {
  margin-left: auto;
  flex: none;
}

.drawer-body {
  flex: 1;
  overflow: auto;
  padding: 14px 16px 22px;
  scrollbar-width: thin;
}

.quote {
  margin: 0;
  border-left: 2px solid var(--teal-line);
  padding: 2px 0 2px 12px;
  font-size: 12.5px;
  color: var(--ink-soft);
  line-height: 1.85;
  white-space: pre-wrap;
}

.drawer-section-title {
  font-size: 10px;
  color: var(--ink-faint);
  margin: 17px 0 7px;
  padding-bottom: 5px;
  border-bottom: 1px solid var(--line-soft);
}

.meta-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 14px;
}

.meta-grid .span-2 {
  grid-column: 1 / -1;
}

.meta-grid .k {
  font-size: 10px;
  color: var(--ink-faint);
}

.meta-grid .v {
  font-size: 11.5px;
  color: var(--ink-soft);
  font-weight: 600;
  word-break: break-all;
}

.meta-grid .v.mono {
  font-family: var(--mono);
  font-size: 10.5px;
}

.provenance {
  margin: 0 0 8px;
  font-size: 10.5px;
  line-height: 1.7;
  color: var(--ink-mute);
}

.provenance--warn {
  color: var(--amber);
  background: var(--amber-soft);
  border: 1px solid var(--amber-line);
  border-radius: var(--r-s);
  padding: 7px 9px;
}

.provenance code {
  font-size: 10px;
  padding: 0 3px;
  border-radius: 3px;
  background: var(--paper-sunken);
}

.kv-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 5px 0;
  font-size: 11.5px;
}

.kv-row .k {
  color: var(--ink-mute);
  flex: none;
}

.kv-row .v {
  color: var(--ink-soft);
  text-align: right;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.threshold-note {
  margin: 6px 0 0;
  font-size: 10px;
  color: var(--ink-faint);
  line-height: 1.7;
}

.warn-box {
  display: flex;
  gap: 9px;
  align-items: flex-start;
  border: 1px solid var(--amber-line);
  background: var(--amber-soft);
  border-radius: var(--r-m);
  padding: 12px 13px;
  color: var(--amber);
}

.warn-box .icon {
  margin-top: 2px;
  flex: none;
}

.warn-title {
  font-size: 12.5px;
  font-weight: 700;
  color: var(--ink);
}

.warn-text {
  margin: 4px 0 0;
  font-size: 11.5px;
  line-height: 1.75;
  color: var(--ink-soft);
}

.drawer-foot {
  padding: 10px 16px;
  border-top: 1px solid var(--line);
  display: flex;
  gap: 7px;
  align-items: center;
}

.foot-note {
  font-size: 10px;
  color: var(--ink-faint);
}

.drawer-foot .mono.foot-note {
  margin-left: auto;
}

.drawer-fade-enter-active,
.drawer-fade-leave-active {
  transition: opacity 0.22s var(--ease);
}

.drawer-fade-enter-from,
.drawer-fade-leave-to {
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .drawer {
    transition: none;
  }
}
</style>
