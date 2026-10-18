/**
 * 页面截图与运行时错误采集。
 * 用本机 Chrome（channel: chrome），不需要下载 Playwright 自带浏览器。
 *
 * 用法：node scripts/shoot.mjs <路径1> [路径2 ...]
 *   例：node scripts/shoot.mjs /chat /wiki /eval /ops
 * 产物：frontend/.artifacts/<slug>.png 与 <slug>.json（控制台错误与失败请求）
 */

import { chromium } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const outDir = join(here, '..', '.artifacts')
const base = process.env.BASE_URL || 'http://127.0.0.1:5173'

const routes = process.argv.slice(2)
if (routes.length === 0) {
  console.error('用法：node scripts/shoot.mjs <路径1> [路径2 ...]')
  process.exit(1)
}

await mkdir(outDir, { recursive: true })

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const results = []

for (const route of routes) {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 1,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
  })
  const page = await context.newPage()

  const consoleErrors = []
  const pageErrors = []
  const failedRequests = []

  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text())
  })
  page.on('pageerror', (error) => pageErrors.push(String(error)))
  page.on('requestfailed', (request) => {
    failedRequests.push(`${request.method()} ${request.url()} :: ${request.failure()?.errorText}`)
  })

  const url = `${base}${route}`
  try {
    await page.goto(url, { waitUntil: 'networkidle', timeout: 30_000 })
  } catch (error) {
    results.push({ route, url, error: String(error) })
    await context.close()
    continue
  }
  // 给异步数据与流式渲染留时间
  await page.waitForTimeout(2500)

  const slug = route.replace(/[^\w]+/g, '_').replace(/^_|_$/g, '') || 'root'
  const shot = join(outDir, `${slug}.png`)
  await page.screenshot({ path: shot, fullPage: false })

  const summary = {
    route,
    url,
    title: await page.title(),
    scrollWidth: await page.evaluate(() => document.documentElement.scrollWidth),
    clientWidth: await page.evaluate(() => document.documentElement.clientWidth),
    consoleErrors,
    pageErrors,
    failedRequests,
    screenshot: shot,
  }
  await writeFile(join(outDir, `${slug}.json`), JSON.stringify(summary, null, 2), 'utf8')
  results.push(summary)
  await context.close()
}

await browser.close()

let bad = 0
for (const item of results) {
  if (item.error) {
    console.log(`[FAIL] ${item.route} :: ${item.error}`)
    bad++
    continue
  }
  const overflow = item.scrollWidth > item.clientWidth
  const errors = item.consoleErrors.length + item.pageErrors.length
  const flag = errors > 0 || overflow ? 'WARN' : ' OK '
  console.log(
    `[${flag}] ${item.route} :: title="${item.title}" 宽度 ${item.clientWidth}/${item.scrollWidth}` +
      `${overflow ? ' 横向溢出' : ''} 控制台错误 ${errors} 失败请求 ${item.failedRequests.length}`,
  )
  for (const line of item.consoleErrors) console.log(`      console: ${line}`)
  for (const line of item.pageErrors) console.log(`      pageerror: ${line}`)
  for (const line of item.failedRequests) console.log(`      request: ${line}`)
  if (errors > 0 || overflow) bad++
}

console.log(bad === 0 ? '\n全部通过' : `\n有 ${bad} 个页面存在问题`)
process.exit(bad === 0 ? 0 : 1)
