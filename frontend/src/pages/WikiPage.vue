<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import AppLayout from '@/layouts/AppLayout.vue'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import SkeletonBlock from '@/components/SkeletonBlock.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { useToast } from '@/composables/useToast'
import { decodeParam } from '@/router'
import { usePageCatalog } from '@/features/wiki/usePageCatalog'
import { usePageDetail } from '@/features/wiki/usePageDetail'
import { useLintReport } from '@/features/wiki/useLintReport'
import { mergeRelatedLinks, activeLinksOf } from '@/features/wiki/links'
import { DOC_SECTIONS } from '@/features/wiki/sections'
import { formatDateTime } from '@/features/wiki/format'
import type { PageFilter } from '@/features/wiki/types'
import PageTree from '@/features/wiki/PageTree.vue'
import PageMeta from '@/features/wiki/PageMeta.vue'
import PageContent from '@/features/wiki/PageContent.vue'
import RetiredNotice from '@/features/wiki/RetiredNotice.vue'
import DocToc from '@/features/wiki/DocToc.vue'
import SourceList from '@/features/wiki/SourceList.vue'
import BacklinkList from '@/features/wiki/BacklinkList.vue'

/**
 * Wiki 条目浏览：左侧条目树 / 中间正文 / 右侧追溯信息。
 * /wiki 未指定条目时落到目录第一条，保证三栏都有内容。
 *
 * 体检要跑一遍模型（实测两分钟左右），所以不在这里自动触发：
 * 右栏只在本次会话已有体检结果时显示摘要，否则引导到条目体检页。
 */

const route = useRoute()
const router = useRouter()
const toast = useToast()

const {
  pages,
  loading: catalogLoading,
  error: catalogError,
  counts,
  ensureLoaded,
} = usePageCatalog()
const { report: lintReport, running: lintRunning, issueTotal } = useLintReport()

const filter = ref<PageFilter>('ALL')
const scroller = ref<HTMLElement | null>(null)

const pageId = computed(() => decodeParam(route.params.pageId))
const { detail, loading, error, reload } = usePageDetail(pageId)

const currentSummary = computed(() => detail.value?.summary ?? null)

const relatedLinks = computed(() => {
  const value = detail.value
  if (!value) return []
  return mergeRelatedLinks(value.content, value.links, pages.value)
})

const activeLinks = computed(() => activeLinksOf(relatedLinks.value))

// 正文四段是固定结构，目录与之一一对应，不按内容有无增删
const sections = DOC_SECTIONS

const breadcrumbGroup = computed(() => {
  const summary = currentSummary.value
  if (!summary) return '条目'
  if (summary.status === 'RETIRED') return '已失效'
  if (summary.status === 'DRAFT') return '草稿'
  return '生效'
})

const contextText = computed(() => {
  const summary = currentSummary.value
  if (summary) return `${summary.version} · 更新于 ${formatDateTime(summary.updatedAt)}`
  return catalogLoading.value ? '加载条目目录' : '选择左侧条目开始阅读'
})

const lintTotal = computed(() => issueTotal(lintReport.value))

/** /wiki 没有 pageId 时补一次跳转；目录为空或加载失败时留在空态 */
watch(
  [() => catalogLoading.value, pages, pageId],
  () => {
    if (pageId.value || catalogLoading.value) return
    const first = pages.value[0]
    if (!first) return
    void router.replace({ name: 'wiki-page', params: { pageId: first.pageId } })
  },
  { immediate: true },
)

void ensureLoaded()

watch(pageId, () => {
  void nextTick(() => {
    scroller.value?.scrollTo({ top: 0 })
  })
})

function onPending(link: { title: string }) {
  toast.info(`「${link.title}」还没有对应条目，暂时无法打开`)
}

async function copyText(value: string, successText: string) {
  try {
    await navigator.clipboard.writeText(value)
    toast.success(successText)
  } catch {
    toast.error('复制失败：浏览器拒绝了剪贴板访问')
  }
}

function retryCatalog() {
  void ensureLoaded(true)
}
</script>

<template>
  <AppLayout>
    <template #context>{{ contextText }}</template>

    <div class="wiki-layout">
      <PageTree
        :pages="pages"
        :current-id="pageId"
        :filter="filter"
        :counts="counts"
        :loading="catalogLoading"
        :error="catalogError"
        @update:filter="filter = $event"
        @retry="retryCatalog"
      />

      <section class="wiki-main" aria-label="条目详情">
        <div class="doc-toolbar">
          <nav class="crumbs" aria-label="面包屑">
            <span>知识目录</span>
            <span class="sep" aria-hidden="true">/</span>
            <span>{{ breadcrumbGroup }}</span>
            <span class="sep" aria-hidden="true">/</span>
            <span class="cur">{{ currentSummary?.title ?? '条目' }}</span>
          </nav>
          <div class="doc-tools">
            <RouterLink class="btn sm" :to="{ name: 'wiki-lint' }">
              <AppIcon name="warning" :size="14" />条目体检
            </RouterLink>
            <button
              class="btn sm"
              type="button"
              :disabled="!currentSummary"
              @click="currentSummary && copyText(currentSummary.pageId, '已复制条目标识')"
            >
              <AppIcon name="copy" :size="14" />复制标识
            </button>
          </div>
        </div>

        <div ref="scroller" class="doc-scroll">
          <div v-if="loading && !detail" class="doc-body doc-body--skeleton" aria-busy="true">
            <SkeletonBlock width="72%" height="26px" />
            <SkeletonBlock :lines="2" height="13px" />
            <SkeletonBlock :lines="6" height="12px" />
          </div>

          <div v-else-if="error" class="doc-body">
            <ErrorState :error="error" retry-text="重新加载条目" @retry="reload" />
          </div>

          <EmptyState
            v-else-if="!detail"
            :title="catalogLoading ? '正在加载条目目录' : '还没有可浏览的条目'"
            :description="
              catalogLoading ? '请稍候。' : '后端 Wiki 尚未编译出条目，先到运维台执行编译。'
            "
          />

          <article v-else class="doc-body" aria-labelledby="doc-title">
            <div class="doc-kicker">
              <StatusBadge :status="detail.summary.status" />
              <span>政策条目</span>
              <span class="dot-sep" aria-hidden="true" />
              <span class="mono">{{ detail.summary.pageId }}</span>
              <span class="dot-sep" aria-hidden="true" />
              <span>更新于 {{ formatDateTime(detail.summary.updatedAt) }}</span>
            </div>

            <h1 id="doc-title" class="doc-title">{{ detail.summary.title }}</h1>

            <PageMeta :summary="detail.summary" />

            <RetiredNotice
              v-if="detail.summary.status === 'RETIRED'"
              :summary="detail.summary"
              :active-links="activeLinks"
              @pending="onPending"
            />

            <PageContent
              :content="detail.content"
              :related-links="relatedLinks"
              @pending="onPending"
            />
          </article>
        </div>
      </section>

      <aside class="wiki-side" aria-label="条目追溯信息">
        <template v-if="detail">
          <DocToc :sections="sections" :scroller="scroller" />

          <section class="side-card" aria-labelledby="meta-head">
            <div class="side-card-head">
              <span id="meta-head">版本与来源</span>
              <StatusBadge :status="detail.summary.status" />
            </div>
            <div class="side-card-body">
              <PageMeta :summary="detail.summary" variant="rows" />
            </div>
          </section>

          <SourceList
            :sources="detail.summary.sources"
            @copy="copyText($event, `已复制来源标识：${$event}`)"
          />

          <BacklinkList
            :links="detail.backlinks"
            :count="detail.summary.inboundCount"
            @pending="onPending"
          />

          <section class="side-card" aria-labelledby="lint-head">
            <div class="side-card-head">
              <span id="lint-head">条目体检</span>
              <span v-if="lintTotal > 0" class="tag amber">{{ lintTotal }} 条</span>
              <span v-else-if="lintReport" class="tag green">通过</span>
              <span v-else class="mono">未运行</span>
            </div>
            <div class="side-card-body">
              <p class="lint-note">
                {{
                  lintRunning
                    ? '体检进行中，完成后在条目体检页查看。'
                    : lintReport
                      ? '本次会话已有体检结果，可在条目体检页查看详情。'
                      : '本次会话还没有体检结果。'
                }}
              </p>
              <RouterLink class="btn sm lint-link" :to="{ name: 'wiki-lint' }">
                <AppIcon name="arrow-right" :size="13" />
                {{ lintRunning ? '查看进度' : '打开条目体检' }}
              </RouterLink>
            </div>
          </section>
        </template>

        <section v-else class="side-card">
          <div class="side-card-head"><span>条目追溯</span></div>
          <div class="side-card-body">
            <p class="lint-note">选中条目后展示目录、来源与反向链接。</p>
          </div>
        </section>
      </aside>
    </div>
  </AppLayout>
</template>

<style scoped>
.wiki-layout {
  display: grid;
  grid-template-columns: 264px minmax(0, 1fr) 286px;
  min-height: 0;
  flex: 1;
  overflow: hidden;
}

.wiki-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: var(--paper-raised);
}

.doc-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 24px;
  border-bottom: 1px solid var(--line);
  min-height: 48px;
}

.crumbs {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: var(--ink-faint);
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
}

.crumbs .sep {
  color: var(--line-strong);
}

.crumbs .cur {
  color: var(--ink-soft);
  overflow: hidden;
  text-overflow: ellipsis;
}

.doc-tools {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}

.doc-tools a {
  text-decoration: none;
}

.doc-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 28px clamp(24px, 5vw, 60px) 44px;
  scrollbar-width: thin;
}

.doc-body {
  max-width: 680px;
  margin: 0 auto;
}

.doc-body--skeleton {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.doc-kicker {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 10.5px;
  color: var(--ink-mute);
  flex-wrap: wrap;
  margin-bottom: 9px;
}

.doc-title {
  font-family: var(--serif);
  font-size: 29px;
  line-height: 1.35;
  font-weight: 700;
  letter-spacing: 0.01em;
  margin: 0 0 8px;
}

.wiki-side {
  padding: 14px 13px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: auto;
  background: var(--paper);
  border-left: 1px solid var(--line);
  scrollbar-width: thin;
}

/* 侧栏整体滚动，卡片自身不参与收缩，否则内容会被压扁裁掉 */
.wiki-side > * {
  flex: none;
}

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

.lint-note {
  margin: 0;
  font-size: 10.5px;
  line-height: 1.7;
  color: var(--ink-faint);
}

.lint-link {
  width: 100%;
  margin-top: 8px;
  text-decoration: none;
}

@media (max-width: 1280px) {
  .wiki-layout {
    grid-template-columns: 238px minmax(0, 1fr) 262px;
  }
}

@media (max-width: 1080px) {
  .wiki-layout {
    grid-template-columns: 230px minmax(0, 1fr);
  }

  .wiki-side {
    display: none;
  }
}

@media (max-width: 760px) {
  .wiki-layout {
    grid-template-columns: 1fr;
  }

  /* 条目树让位给正文：单列时靠正文里的双链与反向链接跳转 */
  .wiki-layout > .rail {
    display: none;
  }

  .doc-scroll {
    padding: 20px 16px 32px;
  }

  .doc-title {
    font-size: 24px;
  }
}
</style>
