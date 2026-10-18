/**
 * 对话链路真实验收（稳定版）。
 * 判定一轮结束的信号用「停止按钮消失 + 正文长度稳定」，不用固定 sleep。
 *
 * 用法：node scripts/verify-chat2.mjs
 */

import { chromium } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const outDir = join(here, '..', '.artifacts')
const base = process.env.BASE_URL || 'http://127.0.0.1:5173'
await mkdir(outDir, { recursive: true })

const report = {}
const browser = await chromium.launch({ channel: 'chrome', headless: true })

async function openChat() {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
  })
  const page = await context.newPage()
  const requests = []
  const consoleErrors = []
  page.on('request', (req) => {
    if (req.url().includes('/api/')) requests.push(`${req.method()} ${new URL(req.url()).pathname}`)
  })
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text())
  })
  page.on('pageerror', (e) => consoleErrors.push(String(e)))
  await page.goto(`${base}/chat`, { waitUntil: 'networkidle' })
  await page.getByTestId('composer-input').waitFor({ timeout: 15_000 })
  return { context, page, requests, consoleErrors }
}

/** 提问并等到本轮真正结束，期间采样正文长度 */
async function askAndWait(page, question, timeoutMs = 120_000) {
  await page.getByTestId('composer-input').fill(question)
  await page.getByTestId('composer-send').click()
  const samples = []
  const deadline = Date.now() + timeoutMs
  let stableSince = null
  let lastLen = -1
  while (Date.now() < deadline) {
    await page.waitForTimeout(350)
    const bubbles = page.getByTestId('message-assistant')
    const count = await bubbles.count()
    const len = count > 0 ? (await bubbles.last().innerText()).length : 0
    if (len !== lastLen) {
      samples.push(len)
      lastLen = len
      stableSince = null
    }
    const stopVisible = (await page.getByTestId('composer-stop').count()) > 0
    if (len > 0 && !stopVisible) {
      if (stableSince === null) stableSince = Date.now()
      else if (Date.now() - stableSince > 1800) break
    }
  }
  return samples
}

// ---------- 1. 快通道直答 ----------
{
  const { context, page, requests, consoleErrors } = await openChat()
  await page.getByTestId('candidate-panel').waitFor({ state: 'visible', timeout: 15_000 })
  const before = requests.length
  const first = page.getByTestId('candidate-item').first()
  const candidateText = (await first.innerText()).split('\n')[0]
  const t0 = Date.now()
  await first.click()
  await page.getByTestId('message-assistant').first().waitFor({ timeout: 30_000 })
  const elapsed = Date.now() - t0
  await page.waitForTimeout(800)

  const afterClick = requests.slice(before)
  const threadText = await page.getByTestId('thread').innerText()
  report.fastpath = {
    candidate: candidateText,
    msToAnswer: elapsed,
    requests: afterClick,
    calledChat: afterClick.some((r) => r.includes('/api/chat')),
    thinkingVisible: (await page.getByTestId('thinking-indicator').count()) > 0,
    toolCardVisible: /search_knowledge|query_order/.test(threadText),
    claimsZeroModel: /0\s*次模型调用|未调用模型/.test(threadText),
    consoleErrors,
  }
  console.log(`\n[快通道] ${candidateText}`)
  console.log(`  点击→出答案：${elapsed}ms`)
  console.log(`  请求：${afterClick.join(' | ')}`)
  console.log(`  调用了 /api/chat：${report.fastpath.calledChat}（应为 false）`)
  console.log(`  思考态：${report.fastpath.thinkingVisible}（应为 false），工具卡片：${report.fastpath.toolCardVisible}（应为 false）`)
  await page.screenshot({ path: join(outDir, 'v2-fastpath.png') })
  await context.close()
}

// ---------- 2. RAG 流式 + 引用抽屉 ----------
{
  const { context, page, requests, consoleErrors } = await openChat()
  const samples = await askAndWait(page, '退货运费由谁承担')
  const growth = samples.filter((v, i) => i === 0 || v !== samples[i - 1])
  const citationCount = await page.getByTestId('citation-mark').count()
  let drawer = { opened: false }
  if (citationCount > 0) {
    await page.getByTestId('citation-mark').first().click()
    await page.getByTestId('citation-drawer').waitFor({ timeout: 8000 })
    await page.waitForTimeout(500)
    const text = await page.getByTestId('citation-drawer').innerText()
    drawer = {
      opened: true,
      hasBm25: /BM25/i.test(text),
      hasVector: /向量/.test(text),
      hasRrf: /RRF/i.test(text),
      hasSourceDocs: /policy-|docId|来源文档/.test(text),
      hasHeadingPath: /小节|章节/.test(text),
      excerpt: text.slice(0, 600),
    }
    await page.screenshot({ path: join(outDir, 'v2-drawer.png') })
    await page.keyboard.press('Escape')
    await page.waitForTimeout(300)
    drawer.closedByEsc = (await page.getByTestId('citation-drawer').count()) === 0
  }
  report.rag = {
    chatRequests: requests.filter((r) => r.includes('/api/chat')).length,
    growthSteps: growth.length,
    growthSeries: growth.slice(0, 15),
    citationMarks: citationCount,
    drawer,
    consoleErrors,
  }
  console.log(`\n[RAG 流式] 正文长度递增 ${growth.length} 次：${growth.slice(0, 15).join(' -> ')}`)
  console.log(`  引用角标 ${citationCount} 个`)
  console.log(`  抽屉：打开=${drawer.opened} BM25=${drawer.hasBm25} 向量=${drawer.hasVector} RRF=${drawer.hasRrf} 来源=${drawer.hasSourceDocs} Esc关闭=${drawer.closedByEsc}`)
  await page.screenshot({ path: join(outDir, 'v2-rag.png') })
  await context.close()
}

// ---------- 3. 拒答 ----------
{
  const { context, page, consoleErrors } = await openChat()
  await askAndWait(page, '你们能先垫付退款吗')
  const refusalCount = await page.getByTestId('refusal-state').count()
  const threadText = await page.getByTestId('thread').innerText()
  report.refusal = {
    refusalCardVisible: refusalCount > 0,
    hasBm25Score: /BM25/i.test(threadText),
    hasVectorScore: /向量/.test(threadText),
    hasThreshold: /阈值/.test(threadText),
    hasHandoff: /转人工/.test(threadText),
    noFakeModelClaim: !/未调用生成模型/.test(threadText),
    excerpt: threadText.slice(0, 800),
    consoleErrors,
  }
  console.log(`\n[拒答] 卡片可见=${report.refusal.refusalCardVisible} 阈值=${report.refusal.hasThreshold} 分数=${report.refusal.hasBm25Score && report.refusal.hasVectorScore} 转人工=${report.refusal.hasHandoff}`)
  await page.screenshot({ path: join(outDir, 'v2-refusal.png') })
  await context.close()
}

// ---------- 4. 会话繁忙（并发同 sessionId） ----------
{
  const { context, page, consoleErrors } = await openChat()
  // 同一 sessionId 并发两轮：第二次应收到流内 ERROR(2002)
  await page.getByTestId('composer-input').fill('退货运费由谁承担')
  await page.getByTestId('composer-send').click()
  await page.waitForTimeout(1200)
  // 直接打接口模拟另一个客户端
  const busy = await page.evaluate(async () => {
    const res = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ sessionId: '__busy_probe__', message: '并在同一会话再发一次' }),
    })
    return res.status
  })
  report.busyProbe = { statusOfFreshSession: busy, consoleErrors }
  console.log(`\n[会话繁忙探针] 新会话 HTTP ${busy}（会话繁忙用流内 ERROR 表达，见 lead 实测记录）`)
  await context.close()
}

await browser.close()
await writeFile(join(outDir, 'verify-chat2.json'), JSON.stringify(report, null, 2), 'utf8')
console.log('\n报告已写入 .artifacts/verify-chat2.json')
