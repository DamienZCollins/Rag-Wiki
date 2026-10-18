import { expect, test, type Locator, type Page } from '@playwright/test'

/**
 * 对话三条关键路径：快通道直答 / RAG 流式问答 / 拒答转人工。
 *
 * 选择器策略：优先 data-testid（团队约定），同时挂语义兜底选择器，
 * 这样 chat-dev 补上 testid 前后都能跑，且不依赖样式 class 的语义。
 * 唯一用到 class 的地方是「抽屉是否打开」的几何判定，原因见 isDrawerOpen。
 */

/** testid 优先，语义选择器兜底：用 CSS 并集一次匹配，避免两次查询之间的时序差 */
function either(testId: string, fallback: string): string {
  return `[data-testid="${testId}"], ${fallback}`
}

const SEL = {
  candidatePanel: either('candidate-panel', 'section[aria-label="快通道候选"]'),
  candidateItem: either('candidate-item', 'section[aria-label="快通道候选"] button.cand'),
  composerInput: either('composer-input', '#composer-input'),
  composerSend: either('composer-send', 'button[aria-label="发送"]'),
  composerStop: either('composer-stop', 'button[aria-label="停止生成"]'),
  thread: either('thread', '.thread'),
  assistantMessage: either('message-assistant', '.msg-block.bot-block'),
  thinking: either('thinking-indicator', '.thinking'),
  citationMark: either('citation-mark', 'button.cite'),
  drawer: either('citation-drawer', '.drawer'),
  refusal: either('refusal-state', '.refusal'),
} as const

function pick(page: Page, selector: string): Locator {
  return page.locator(selector)
}

/** 每条用例用独立会话，避免上一轮上下文影响下一轮 */
function uniqueSession(): string {
  return `e2e-${Date.now()}-${Math.floor(Math.random() * 1000)}`
}

async function openChat(page: Page) {
  await page.goto(`/chat/${uniqueSession()}`)
  await expect(pick(page, SEL.thread)).toBeVisible({ timeout: 30_000 })
  await expect(pick(page, SEL.composerInput)).toBeVisible({ timeout: 30_000 })
}

async function ask(page: Page, question: string) {
  await pick(page, SEL.composerInput).fill(question)
  await pick(page, SEL.composerSend).click()
}

/** 等本轮结束：停止按钮消失即流已收尾（FINISHED 是唯一终止信号） */
async function waitForTurnEnd(page: Page, timeout = 180_000) {
  await expect(pick(page, SEL.composerStop)).toHaveCount(0, { timeout })
}

/**
 * 抽屉是否处于打开态。
 * 抽屉组件常驻 DOM（关闭时 transform 移出视口），因此不能用 toBeVisible 判断，
 * 这里用 aria-hidden 与包围盒是否落在视口内两个事实综合判定，对实现细节不敏感。
 */
async function isDrawerOpen(page: Page): Promise<boolean> {
  return page.evaluate((selector) => {
    const element = document.querySelector(selector)
    if (!(element instanceof HTMLElement)) return false
    if (element.getAttribute('aria-hidden') === 'true') return false
    const rect = element.getBoundingClientRect()
    if (rect.width === 0 || rect.height === 0) return false
    // 关闭态用 translateX(102%) 移出右侧视口
    return rect.left < window.innerWidth - 1 && rect.right > 1
  }, SEL.drawer)
}

test.describe('售后对话', () => {
  test('快通道直答：点候选即出答案，全程没有思考态', async ({ page }) => {
    await openChat(page)

    const panel = pick(page, SEL.candidatePanel)
    await expect(panel).toBeVisible({ timeout: 30_000 })

    const candidate = pick(page, SEL.candidateItem).first()
    await expect(candidate).toBeVisible({ timeout: 30_000 })
    const question = (await candidate.innerText()).trim()
    expect(question.length, '候选条目没有文本').toBeGreaterThan(0)

    await test.step('点候选前装观察器，记录本轮是否出现过思考态', async () => {
      // 快通道命中时事件流里没有 THINKING，界面也不应出现思考指示器；
      // 用 MutationObserver 抓整轮过程，避免只在收尾时刻采样漏掉中间闪现
      await page.evaluate((thinkingSelector) => {
        const state = { seen: false }
        ;(window as unknown as { __thinkingSeen: typeof state }).__thinkingSeen = state
        const observer = new MutationObserver(() => {
          if (document.querySelector(thinkingSelector)) state.seen = true
        })
        observer.observe(document.body, { childList: true, subtree: true })
        ;(window as unknown as { __thinkingObserver: MutationObserver }).__thinkingObserver =
          observer
      }, SEL.thinking)
    })

    await test.step('点击候选后作答出现', async () => {
      await candidate.click()
      const answer = pick(page, SEL.assistantMessage).last()
      await expect(answer).toBeVisible({ timeout: 30_000 })
      await expect
        .poll(async () => (await answer.innerText()).trim().length, {
          timeout: 30_000,
          message: '快通道答案 30 秒内没有渲染出正文',
        })
        .toBeGreaterThan(10)
    })

    await test.step('全程没有出现思考态（快通道的判据）', async () => {
      await expect(pick(page, SEL.thinking)).toHaveCount(0)
      const seen = await page.evaluate(
        () => (window as unknown as { __thinkingSeen?: { seen: boolean } }).__thinkingSeen?.seen,
      )
      expect(seen, '快通道这一轮出现过思考态，说明没有走确定性直答').toBe(false)
    })

    await test.step('答案标注 0 次模型调用，且没有引用角标', async () => {
      const answer = pick(page, SEL.assistantMessage).last()
      await expect(answer).toContainText(/0 次模型调用|快通道/)
      await expect(pick(page, SEL.citationMark)).toHaveCount(0)
    })
  })

  test('RAG 流式问答：流式渲染结束后引用角标可点开抽屉', async ({ page }) => {
    test.setTimeout(240_000)
    await openChat(page)

    await test.step('发送需要检索的问题', async () => {
      await ask(page, '退货的运费到底谁掏？')
      await expect(pick(page, SEL.assistantMessage).last()).toBeVisible({ timeout: 30_000 })
    })

    await test.step('流式渲染结束并产出正文', async () => {
      await waitForTurnEnd(page)
      const answer = pick(page, SEL.assistantMessage).last()
      await expect
        .poll(async () => (await answer.innerText()).trim().length, { timeout: 60_000 })
        .toBeGreaterThan(20)
    })

    await test.step('引用角标出现并可点开抽屉', async () => {
      const mark = pick(page, SEL.citationMark).first()
      await expect(mark).toBeVisible({ timeout: 30_000 })
      expect(await isDrawerOpen(page), '点角标前抽屉应是关闭的').toBe(false)

      await mark.click()
      await expect.poll(async () => isDrawerOpen(page), { timeout: 15_000 }).toBe(true)

      const drawer = pick(page, SEL.drawer)
      // 抽屉里应有片段正文与溯源信息，而不是空壳
      await expect
        .poll(async () => (await drawer.innerText()).trim().length, { timeout: 15_000 })
        .toBeGreaterThan(20)
      await expect(drawer).toContainText(/引用原文|溯源|来源/)
    })

    await test.step('Esc 关闭抽屉', async () => {
      await page.keyboard.press('Escape')
      await expect.poll(async () => isDrawerOpen(page), { timeout: 10_000 }).toBe(false)
    })
  })

  test('拒答：超范围问题给出拒答卡片并可转人工', async ({ page }) => {
    test.setTimeout(600_000)

    /**
     * 拒答的触发条件（后端事实，实测核实）：
     * - Agent 必须真的调用了 search_knowledge，拿回 hit=false；
     * - 且该问题用 /api/rag/search 复算时 BM25 与向量两路最高分都低于各自阈值。
     *
     * 因此不是所有「超范围问题」都会出拒答卡片：
     * 「帮我订机票 / 今天天气怎么样」这类一眼无关的问题，模型直接不检索、礼貌回绝，
     * 全程没有 toolCall，自然也不会走拒答分支（实测 toolCalls=0）。
     * 与业务近邻但知识库没有答案的问题才会检索→hit=false→两路分数均不达标→拒答卡片。
     *
     * 这里用的三句都是实测能稳定出卡片的：BM25 5.19~5.57（阈值 8）、向量 0.588（阈值 0.72）。
     */
    const refusalQuestions = [
      '发票的税率是多少？',
      '你们平台的会员卡怎么办理？',
      '支持货到付款吗？',
    ]

    let seen = false

    for (const question of refusalQuestions) {
      if (seen) break

      await test.step(`提问：${question}`, async () => {
        await openChat(page)
        await ask(page, question)
        await waitForTurnEnd(page)

        const refusal = pick(page, SEL.refusal)
        seen = await refusal
          .waitFor({ state: 'visible', timeout: 45_000 })
          .then(() => true)
          .catch(() => false)

        if (!seen) {
          const last = pick(page, SEL.assistantMessage).last()
          const said = (await last.innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 200)
          test.info().annotations.push({
            type: 'missed-refusal',
            description: `「${question}」没有出拒答卡片，模型答复：${said}`,
          })
        }
      })
    }

    expect(
      seen,
      `连续 ${refusalQuestions.length} 句近邻超范围问题都没有渲染出拒答卡片。` +
        '后端只有在 Agent 调用检索且两路分数均低于阈值时才拒答，请查看上方 annotation 中模型的实际答复。',
    ).toBe(true)

    await test.step('拒答卡片写明检索侧没有依据，并给出两路分数对比', async () => {
      const refusal = pick(page, SEL.refusal)
      await expect(refusal).toContainText(/没有能支撑|没有找到|依据/)
      await expect(refusal).toContainText(/BM25/)
      await expect(refusal).toContainText(/阈值/)
    })

    await test.step('拒答这一轮没有可点开的引用', async () => {
      // chunks 为空，不应渲染任何引用角标
      await expect(pick(page, SEL.citationMark)).toHaveCount(0)
    })

    await test.step('可以转人工', async () => {
      const handoff = pick(page, SEL.refusal).getByRole('button', { name: /转人工/ })
      await expect(handoff).toBeVisible()
      await handoff.click()
      await expect(page.getByText(/已记录|已转人工/).first()).toBeVisible({ timeout: 10_000 })
    })
  })
})
