<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { useThemeStore } from '@/stores/theme'

/** 三视图共用外壳：左侧导航、顶部标题栏、内容区。原型里的通知、更多设置、全局搜索已移除（后端无接口） */

const route = useRoute()
const theme = useThemeStore()

interface NavItem {
  key: string
  label: string
  to: string
  icon: 'chat' | 'chart' | 'book' | 'layers'
}

const navItems: NavItem[] = [
  { key: 'chat', label: '售后对话', to: '/chat', icon: 'chat' },
  { key: 'wiki', label: 'Wiki 条目', to: '/wiki', icon: 'book' },
  { key: 'eval', label: '评测与归因', to: '/eval', icon: 'chart' },
  { key: 'ops', label: '运维操作台', to: '/ops', icon: 'layers' },
]

const activeNav = computed(() => (route.meta.nav as string | undefined) ?? '')
const pageTitle = computed(() => (route.meta.title as string | undefined) ?? 'RAG Wiki')

const themeLabel = computed(() => (theme.theme === 'dark' ? '切换为浅色主题' : '切换为深色主题'))
</script>

<template>
  <div class="app">
    <aside class="sidebar" aria-label="主导航">
      <div class="brand">
        <div class="brand-mark" aria-hidden="true">档</div>
        <div>
          <div class="brand-name">RAG Wiki</div>
          <div class="brand-sub">售后知识工作台</div>
        </div>
      </div>

      <nav class="sidebar-scroll">
        <div class="nav-section">
          <div class="nav-label">工作台</div>
          <RouterLink
            v-for="item in navItems"
            :key="item.key"
            class="nav-item"
            :to="item.to"
            :aria-current="activeNav === item.key ? 'page' : undefined"
          >
            <AppIcon :name="item.icon" :size="16" />
            {{ item.label }}
          </RouterLink>
        </div>
      </nav>

      <div class="sidebar-foot">
        <button class="theme-row" type="button" @click="theme.toggle()">
          <AppIcon :name="theme.theme === 'dark' ? 'sun' : 'moon'" :size="16" />
          {{ themeLabel }}
        </button>
      </div>
    </aside>

    <main class="main">
      <header class="topbar">
        <div class="page-head">
          <h1 class="page-title">{{ pageTitle }}</h1>
          <span class="page-context">
            <slot name="context" />
          </span>
        </div>
        <div class="top-tools">
          <button
            class="icon-btn"
            type="button"
            :aria-label="themeLabel"
            :title="themeLabel"
            @click="theme.toggle()"
          >
            <AppIcon :name="theme.theme === 'dark' ? 'sun' : 'moon'" />
          </button>
        </div>
      </header>

      <div class="view">
        <slot />
      </div>
    </main>
  </div>
</template>

<style scoped>
.app {
  height: 100vh;
  max-width: 1800px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: var(--sidebar-w) minmax(0, 1fr);
  background: var(--paper-raised);
  box-shadow: var(--shadow);
}

.sidebar {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--paper);
  border-right: 1px solid var(--line);
}

.brand {
  padding: 22px 20px 16px;
  display: flex;
  align-items: center;
  gap: 11px;
}

.brand-mark {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  flex: none;
  display: grid;
  place-items: center;
  background: var(--ink);
  color: var(--paper-raised);
  font-family: var(--serif);
  font-size: 20px;
  font-weight: 700;
  line-height: 1;
}

.brand-name {
  font-family: var(--serif);
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 0.02em;
  line-height: 1.2;
}

.brand-sub {
  font-size: 11px;
  color: var(--ink-mute);
  margin-top: 2px;
  letter-spacing: 0.04em;
}

.sidebar-scroll {
  flex: 1;
  overflow: auto;
  padding: 0 10px 12px;
  scrollbar-width: thin;
}

.nav-section {
  margin-top: 18px;
}

.nav-label {
  font-size: 11px;
  color: var(--ink-faint);
  padding: 0 10px 6px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.nav-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: var(--r-s);
  color: var(--ink-soft);
  font-size: 13px;
  text-align: left;
  text-decoration: none;
  transition:
    background 0.15s,
    color 0.15s;
}

.nav-item :deep(.icon) {
  color: var(--ink-mute);
  width: 16px;
  height: 16px;
}

.nav-item:hover {
  background: var(--paper-deep);
  color: var(--ink);
}

.nav-item[aria-current='page'] {
  background: var(--paper-raised);
  color: var(--ink);
  font-weight: 600;
  box-shadow: inset 0 0 0 1px var(--line);
}

.nav-item[aria-current='page'] :deep(.icon) {
  color: var(--oxblood);
}

.sidebar-foot {
  border-top: 1px solid var(--line);
  padding: 10px;
}

.theme-row {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: var(--r-s);
  font-size: 12.5px;
  color: var(--ink-soft);
  text-align: left;
}

.theme-row:hover {
  background: var(--paper-deep);
  color: var(--ink);
}

.theme-row :deep(.icon) {
  color: var(--ink-mute);
  width: 16px;
  height: 16px;
}

.main {
  display: grid;
  grid-template-rows: var(--topbar-h) minmax(0, 1fr);
  min-width: 0;
  min-height: 0;
  background: var(--paper-raised);
}

.topbar {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 18px 0 24px;
  border-bottom: 1px solid var(--line);
  position: relative;
  z-index: 10;
}

.page-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}

.page-title {
  font-family: var(--serif);
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 0.02em;
  white-space: nowrap;
  margin: 0;
}

.page-context {
  font-size: 11.5px;
  color: var(--ink-mute);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}

.top-tools {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}

.view {
  min-height: 0;
  min-width: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.view > :deep(*) {
  flex: 1;
  min-height: 0;
}

@media (max-width: 1280px) {
  .app {
    grid-template-columns: 216px minmax(0, 1fr);
  }
}

@media (max-width: 1080px) {
  .app {
    height: auto;
    min-height: 100vh;
    grid-template-columns: 1fr;
  }

  .sidebar {
    flex-direction: row;
    align-items: center;
    border-right: 0;
    border-bottom: 1px solid var(--line);
    overflow-x: auto;
  }

  .brand {
    padding: 12px 14px;
    flex: none;
  }

  .sidebar-scroll {
    display: flex;
    gap: 3px;
    padding: 8px;
    flex: 1;
    overflow: visible;
  }

  .nav-section {
    display: flex;
    gap: 3px;
    margin-top: 0;
  }

  .nav-label {
    display: none;
  }

  .nav-item {
    white-space: nowrap;
    width: auto;
  }

  .sidebar-foot {
    display: none;
  }

  .main {
    grid-template-rows: 54px minmax(0, 1fr);
    min-height: calc(100vh - 58px);
  }
}

@media (max-width: 760px) {
  .page-context {
    display: none;
  }

  .topbar {
    padding: 0 12px;
    gap: 8px;
  }

  .brand-sub {
    display: none;
  }
}
</style>
