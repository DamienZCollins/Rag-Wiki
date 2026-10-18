/**
 * 主题持久化验证：同一 context 内切换 → 刷新 → 客户端路由 → 整页导航，全程保持所选主题。
 * 用法：node scripts/verify-theme-persist.mjs
 */

import { chromium } from '@playwright/test'

const base = process.env.BASE_URL || 'http://127.0.0.1:5173'
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()

const read = () =>
  page.evaluate(() => ({
    theme: document.documentElement.dataset.theme,
    stored: localStorage.getItem('ragwiki.theme'),
    path: location.pathname,
    bg: getComputedStyle(document.body).backgroundColor,
  }))

const steps = []
const record = async (label) => {
  const s = await read()
  steps.push({ label, ...s })
  console.log(`${label.padEnd(22)} theme=${String(s.theme).padEnd(5)} stored=${String(s.stored).padEnd(5)} bg=${s.bg}`)
  return s
}

await page.goto(`${base}/chat`, { waitUntil: 'networkidle' })
await record('1. 首次打开')

// 顶栏主题按钮是 icon-btn，用 title/aria-label 精确匹配
const toggle = page.locator('button[aria-label^="切换为"]').first()
await toggle.click()
await page.waitForTimeout(600)
const afterToggle = await record('2. 切换后')

await page.reload({ waitUntil: 'networkidle' })
await page.waitForTimeout(900)
const afterReload = await record('3. 刷新后')

// 整页导航到另一个路由
await page.goto(`${base}/wiki`, { waitUntil: 'networkidle' })
await page.waitForTimeout(1000)
const crossRoute = await record('4. 整页到 /wiki')

// 客户端路由
await page.goto(`${base}/chat`, { waitUntil: 'networkidle' })
await page.waitForTimeout(600)
await page.getByRole('link', { name: /评测与归因/ }).first().click()
await page.waitForTimeout(1200)
const clientRoute = await record('5. 客户端路由')

const pass =
  afterToggle.theme === 'dark' &&
  afterToggle.stored === 'dark' &&
  afterReload.theme === 'dark' &&
  crossRoute.theme === 'dark' &&
  clientRoute.theme === 'dark'

console.log(
  pass
    ? '\n[ OK ] 切换、刷新、整页导航、客户端路由 四步主题均保持 dark'
    : '\n[FAIL] 主题持久化不符合预期',
)
await browser.close()
process.exit(pass ? 0 : 1)
