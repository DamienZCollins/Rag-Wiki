/**
 * 三档断点全路由检查：横向溢出、控制台错误。
 * 用法：node scripts/verify-breakpoints.mjs
 */

import { chromium } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const outDir = join(here, '..', '.artifacts')
const base = process.env.BASE_URL || 'http://127.0.0.1:5173'

const routes = ['/chat', '/wiki', '/wiki/lint', '/eval', '/eval/run-1792057705979-46fb0b2d', '/ops']
const widths = [1440, 1280, 1080, 760]

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const rows = []
let bad = 0

for (const width of widths) {
  for (const route of routes) {
    const context = await browser.newContext({
      viewport: { width, height: 900 },
      locale: 'zh-CN',
      timezoneId: 'Asia/Shanghai',
    })
    const page = await context.newPage()
    const errors = []
    page.on('console', (m) => {
      if (m.type() === 'error') errors.push(m.text())
    })
    page.on('pageerror', (e) => errors.push(String(e)))

    try {
      await page.goto(`${base}${route}`, { waitUntil: 'networkidle', timeout: 30_000 })
      await page.waitForTimeout(1800)
      const metrics = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth,
        bodyScrollWidth: document.body.scrollWidth,
      }))
      const overflow = metrics.scrollWidth > metrics.clientWidth + 1
      const row = {
        width,
        route,
        ...metrics,
        overflow,
        consoleErrors: errors,
      }
      rows.push(row)
      if (overflow || errors.length > 0) bad++
      const flag = overflow || errors.length > 0 ? 'FAIL' : ' OK '
      console.log(
        `[${flag}] ${String(width).padStart(4)}px ${route.padEnd(36)} scroll=${metrics.scrollWidth} client=${metrics.clientWidth} 错误=${errors.length}`,
      )
      for (const line of errors) console.log(`        console: ${line}`)
    } catch (error) {
      rows.push({ width, route, error: String(error) })
      bad++
      console.log(`[FAIL] ${width}px ${route} :: ${error}`)
    }
    await context.close()
  }
}

await browser.close()
await writeFile(join(outDir, 'breakpoints.json'), JSON.stringify(rows, null, 2), 'utf8')
console.log(
  bad === 0
    ? `\n全部通过：${rows.length} 组（${widths.length} 档 × ${routes.length} 路由）`
    : `\n有 ${bad} 组存在问题`,
)
process.exit(bad === 0 ? 0 : 1)
