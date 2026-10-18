import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

/**
 * 路由表。后端无鉴权，全部路由公开，不设权限守卫。
 * /wiki/:pageId 的 pageId 含中文，路由参数统一编码与解码。
 */

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/chat',
  },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('@/pages/ChatPage.vue'),
    meta: { title: '售后对话', nav: 'chat' },
  },
  {
    path: '/chat/:sessionId',
    name: 'chat-session',
    component: () => import('@/pages/ChatPage.vue'),
    meta: { title: '售后对话', nav: 'chat' },
  },
  {
    path: '/wiki',
    name: 'wiki',
    component: () => import('@/pages/WikiPage.vue'),
    meta: { title: 'Wiki 条目', nav: 'wiki' },
  },
  {
    path: '/wiki/lint',
    name: 'wiki-lint',
    component: () => import('@/pages/WikiLintPage.vue'),
    meta: { title: '条目体检', nav: 'wiki' },
  },
  {
    path: '/wiki/:pageId',
    name: 'wiki-page',
    component: () => import('@/pages/WikiPage.vue'),
    meta: { title: 'Wiki 条目', nav: 'wiki' },
  },
  {
    path: '/eval',
    name: 'eval',
    component: () => import('@/pages/EvalPage.vue'),
    meta: { title: '评测与归因', nav: 'eval' },
  },
  {
    path: '/eval/:runId',
    name: 'eval-run',
    component: () => import('@/pages/EvalPage.vue'),
    meta: { title: '评测与归因', nav: 'eval' },
  },
  {
    path: '/eval/:runId/cases',
    name: 'eval-cases',
    component: () => import('@/pages/EvalCasesPage.vue'),
    meta: { title: '失败用例明细', nav: 'eval' },
  },
  {
    path: '/ops',
    name: 'ops',
    component: () => import('@/pages/OpsPage.vue'),
    meta: { title: '运维操作台', nav: 'ops' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/pages/NotFoundPage.vue'),
    meta: { title: '页面不存在' },
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(to, _from, savedPosition) {
    // 浏览器前进后退保留位置，其余切换回到顶部
    if (savedPosition) {
      return savedPosition
    }
    if (to.hash) {
      return { el: to.hash, behavior: 'smooth' }
    }
    return { top: 0 }
  },
})

router.afterEach((to) => {
  const title = (to.meta.title as string | undefined) ?? 'RAG Wiki'
  document.title = `${title} · RAG Wiki`
})

/** pageId 路由参数解码：vue-router 已解一次，这里容错再解一次 */
export function decodeParam(value: string | string[] | undefined): string {
  if (Array.isArray(value)) return value[0] ?? ''
  if (!value) return ''
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

export default router
