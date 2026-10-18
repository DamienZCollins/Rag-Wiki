<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import AppLayout from '@/layouts/AppLayout.vue'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import SkeletonBlock from '@/components/SkeletonBlock.vue'
import { useToast } from '@/composables/useToast'
import LintPanel from '@/features/wiki/LintPanel.vue'
import { useLintReport } from '@/features/wiki/useLintReport'
import { formatDateTime, formatDuration, formatRelative } from '@/features/wiki/format'

/**
 * 条目体检页。四类问题分区展示，每条都能点到具体条目。
 *
 * 体检一次要跑模型（实测约两分钟），所以不自动触发：
 * 首次进入展示上次结果或空态，由用户点「重新体检」发起。
 */

const toast = useToast()
const { report, running, error, finishedAt, elapsedMs, refresh, issueTotal } = useLintReport()

const now = ref(Date.now())
const hasRunThisVisit = ref(false)

const total = computed(() => issueTotal(report.value))

const finishedText = computed(() => {
  if (!finishedAt.value) return ''
  return formatDateTime(new Date(finishedAt.value).toISOString())
})

const elapsedText = computed(() =>
  elapsedMs.value === null ? '' : formatDuration(elapsedMs.value),
)

const relativeText = computed(() => {
  if (!finishedAt.value) return ''
  return formatRelative(finishedAt.value, now.value)
})

const stats = computed(() => {
  const value = report.value
  return [
    { key: 'CONFLICT', label: '矛盾', count: value?.conflicts.length ?? 0 },
    { key: 'EXPIRED', label: '过期', count: value?.expired.length ?? 0 },
    { key: 'ORPHAN', label: '孤页', count: value?.orphans.length ?? 0 },
    { key: 'PENDING_LINK', label: '待创建', count: value?.pendingLinks.length ?? 0 },
  ]
})

async function rerun() {
  now.value = Date.now()
  await refresh()
  hasRunThisVisit.value = true
  now.value = Date.now()

  if (error.value) {
    toast.error('体检失败，请稍后重试')
    return
  }
  toast.success(
    total.value === 0 ? '体检完成：四类检查全部通过' : `体检完成：共 ${total.value} 条问题`,
  )
}

onMounted(() => {
  now.value = Date.now()
})
</script>

<template>
  <AppLayout>
    <template #context>
      <template v-if="report">
        检查 {{ report.pageCount }} 个条目 · {{ finishedText || '尚未完成' }}
      </template>
      <template v-else>矛盾 / 过期 / 孤页 / 待创建</template>
    </template>

    <div class="lint-page">
      <div class="lint-page-inner">
        <header class="lint-head">
          <div class="lint-head-main">
            <h2 class="lint-title serif">条目体检</h2>
            <p class="lint-sub">
              检查条目的四类问题：矛盾、过期、孤页与待创建双链。每条都能定位到具体条目。
            </p>
          </div>
          <div class="lint-head-actions">
            <RouterLink class="btn sm" :to="{ name: 'wiki' }">
              <AppIcon name="book" :size="14" />返回条目浏览
            </RouterLink>
            <button class="btn primary sm" type="button" :disabled="running" @click="rerun">
              <AppIcon name="refresh" :size="14" />
              {{ running ? '体检中…' : '重新体检' }}
            </button>
          </div>
        </header>

        <div class="kpi-row">
          <div v-for="item in stats" :key="item.key" class="kpi">
            <span class="kpi-label">{{ item.label }}</span>
            <span class="kpi-value mono" :class="{ 'kpi-value--zero': item.count === 0 }">
              {{ item.count }}
            </span>
          </div>
          <div class="kpi kpi--total">
            <span class="kpi-label">问题总数</span>
            <span class="kpi-value mono" :class="{ 'kpi-value--zero': total === 0 }">
              {{ total }}
            </span>
          </div>
        </div>

        <p v-if="report" class="lint-stamp">
          <AppIcon name="check" :size="12" />
          本次结果：{{ finishedText }}
          <template v-if="elapsedText">，耗时 {{ elapsedText }}</template>
          <template v-if="relativeText">（{{ relativeText }}）</template>
          <span class="dot-sep" aria-hidden="true" />
          共检查 {{ report.pageCount }} 个条目
        </p>

        <div v-if="running" class="lint-running" aria-busy="true">
          <p class="lint-running-text">
            正在跑体检：先按向量相似度筛出相关条目对，再让编译模型判定冲突，通常需要两分钟左右。
          </p>
          <SkeletonBlock :lines="4" height="12px" />
        </div>

        <ErrorState v-else-if="error" :error="error" retry-text="重新体检" @retry="rerun" />

        <template v-else-if="report">
          <LintPanel :report="report" />
        </template>

        <EmptyState
          v-else
          title="还没有体检结果"
          description="体检会调用编译模型判定条目间的矛盾，一次约两分钟。点右上角「重新体检」开始。"
          action-text="重新体检"
          @action="rerun"
        />

        <p v-if="hasRunThisVisit && report" class="lint-foot">
          结果来自本次体检，未做缓存跨会话保留；刷新页面后可再次发起。
        </p>
      </div>
    </div>
  </AppLayout>
</template>

<style scoped>
.lint-page {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 26px clamp(20px, 4vw, 48px) 40px;
  scrollbar-width: thin;
}

.lint-page-inner {
  max-width: 880px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.lint-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.lint-head-main {
  min-width: 0;
}

.lint-title {
  font-size: 22px;
  font-weight: 700;
  margin: 0;
  letter-spacing: 0.01em;
}

.lint-sub {
  margin: 5px 0 0;
  font-size: 11.5px;
  line-height: 1.75;
  color: var(--ink-mute);
  max-width: 560px;
}

.lint-head-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}

.lint-head-actions a {
  text-decoration: none;
}

.kpi-row {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
}

.kpi {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-m);
  background: var(--paper-raised);
}

.kpi--total {
  background: var(--paper);
}

.kpi-label {
  font-size: 10.5px;
  color: var(--ink-mute);
}

.kpi-value {
  font-size: 19px;
  font-weight: 700;
  line-height: 1.2;
  color: var(--ink);
}

.kpi-value--zero {
  color: var(--ink-faint);
}

.lint-stamp {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin: 0;
  font-size: 10.5px;
  color: var(--ink-faint);
}

.lint-stamp :deep(.icon) {
  width: 12px;
  height: 12px;
  color: var(--green);
}

.lint-running {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: var(--r-m);
  background: var(--paper-raised);
}

.lint-running-text {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.75;
  color: var(--ink-mute);
}

.lint-foot {
  margin: 0;
  font-size: 10.5px;
  line-height: 1.7;
  color: var(--ink-faint);
}

@media (max-width: 760px) {
  .kpi-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .lint-head-actions {
    width: 100%;
  }
}
</style>
