import { expect, test, type Locator, type Page } from '@playwright/test'

/**
 * 评测与归因（/eval）端到端测试。
 *
 * 只读页面：切批次、看指标、按归因层筛选失败用例，全部来自 GET 接口。
 * 不触发跑批、基线固化等写操作。
 *
 * 选择器策略：面板按 h2 标题定位，行按 caseId 文本定位，
 * 筛选条用 role=group + aria-pressed 判定选中态，不依赖样式 class。
 */

/** 按面板标题定位面板。评测页里 h1 与外壳标题重名，故统一按 h2 面板标题取 */
function panel(page: Page, title: string): Locator {
  return page.locator('section.panel').filter({
    has: page.getByRole('heading', { name: title, exact: true }),
  })
}

test.describe('评测与归因', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/eval')
    // 页面正文里的 h1（与外壳顶栏标题同名，故限定在正文容器内）
    await expect(page.locator('.eval-inner h1')).toHaveText('评测与归因')
  })

  test('批次切换：指标随之刷新且数字始终归属当前 runId', async ({ page }) => {
    const selector = panel(page, '评测批次')

    await test.step('批次列表载入并默认选中最近一批', async () => {
      await expect(selector.getByText(/共 \d+ 批/)).toBeVisible({ timeout: 20_000 })
      await expect(selector.locator('.run-row').first()).toHaveAttribute('aria-current', 'true')
    })

    const runIds = await selector.locator('.run-row .run-id').allInnerTexts()
    expect(runIds.length, '批次列表至少要有两条才能验证切换').toBeGreaterThan(1)

    const firstRunId = runIds[0].trim()
    const secondRunId = runIds[1].trim()

    await test.step('标题区显示当前批次 runId', async () => {
      await expect(page.locator('.eval-sub')).toContainText(firstRunId)
    })

    const metricsBefore = await page.locator('.eval-inner').innerText()

    await test.step('切换到第二条批次', async () => {
      await selector.locator('.run-row').nth(1).click()
      await expect(page).toHaveURL(new RegExp(secondRunId.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
      await expect(page.locator('.eval-sub')).toContainText(secondRunId)
    })

    await test.step('指标区随批次重新渲染', async () => {
      await expect(panel(page, '指标').first()).toBeVisible({ timeout: 20_000 })
      // 换批后正文内容必然变化（runId 与指标都不同），借此确认不是同一份数据
      await expect
        .poll(async () => (await page.locator('.eval-inner').innerText()) !== metricsBefore, {
          timeout: 20_000,
        })
        .toBe(true)
    })
  })

  test('归因筛选：按层过滤失败用例并显示命中条数', async ({ page }) => {
    const failures = panel(page, '失败用例')
    await expect(failures).toBeVisible({ timeout: 20_000 })

    const chips = failures.getByRole('group', { name: '按归因层筛选' }).getByRole('button')
    await expect(chips.first()).toBeVisible()

    const allChip = chips.first()
    await expect(allChip).toHaveAttribute('aria-pressed', 'true')

    // 选一个有条数的层（条数为 0 的层按钮是禁用的）
    const enabledChips = failures
      .getByRole('group', { name: '按归因层筛选' })
      .getByRole('button')
      .filter({ hasNotText: '全部' })
    const count = await enabledChips.count()
    let picked: Locator | null = null
    for (let index = 0; index < count; index += 1) {
      const chip = enabledChips.nth(index)
      if (await chip.isEnabled()) {
        picked = chip
        break
      }
    }
    expect(picked, '没有可用的归因层筛选项').not.toBeNull()
    const pickedName = ((await picked!.innerText()) || '').split('\n')[0].trim()

    await test.step(`点击「${pickedName}」层`, async () => {
      await picked!.click()
      await expect(picked!).toHaveAttribute('aria-pressed', 'true')
      await expect(allChip).toHaveAttribute('aria-pressed', 'false')
    })

    await test.step('列表只剩该层用例，且条数与筛选条一致', async () => {
      const chipText = await picked!.innerText()
      const chipCount = Number(chipText.match(/(\d+)\s*$/)?.[1] ?? '0')
      expect(chipCount).toBeGreaterThan(0)

      const rows = failures.locator('.failure-item')
      await expect(rows).toHaveCount(Math.min(chipCount, 8))
      // 每行的归因徽章都应是该层
      const badges = await rows.locator('.failure-row').allInnerTexts()
      for (const text of badges) {
        expect(text).toContain(pickedName)
      }
    })

    await test.step('切回全部后恢复完整列表', async () => {
      await allChip.click()
      await expect(allChip).toHaveAttribute('aria-pressed', 'true')
      await expect(failures.locator('.failure-item').first()).toBeVisible()
    })
  })

  test('失败用例可展开归因链路', async ({ page }) => {
    const failures = panel(page, '失败用例')
    await expect(failures).toBeVisible({ timeout: 20_000 })

    const firstRow = failures.locator('.failure-row').first()
    await expect(firstRow).toBeVisible({ timeout: 20_000 })
    await expect(firstRow).toHaveAttribute('aria-expanded', 'false')

    await test.step('点击行后展开归因链路', async () => {
      await firstRow.click()
      await expect(firstRow).toHaveAttribute('aria-expanded', 'true')
      const trace = failures.locator('[id^="trace-"]').first()
      await expect(trace).toBeVisible()
      expect((await trace.innerText()).length).toBeGreaterThan(0)
    })
  })

  test('门禁面板展示真实判定与容差', async ({ page }) => {
    const gate = page
      .locator('section.panel')
      .filter({ has: page.getByRole('heading', { name: /门禁/ }) })
      .first()

    await test.step('门禁面板可见且含容差或基线说明', async () => {
      await expect(gate).toBeVisible({ timeout: 20_000 })
      const text = await gate.innerText()
      expect(text).toMatch(/容差|基线/)
    })
  })

  test('1280 / 1080 / 760 三档无横向溢出', async ({ page }) => {
    await expect(page.locator('.eval-inner h1')).toBeVisible()
    for (const width of [1280, 1080, 760]) {
      await test.step(`${width}px 下无横向滚动`, async () => {
        await page.setViewportSize({ width, height: 900 })
        const overflow = await page.evaluate(
          () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
        )
        expect(overflow, `${width}px 下出现横向溢出 ${overflow}px`).toBeLessThanOrEqual(1)
      })
    }
  })
})
