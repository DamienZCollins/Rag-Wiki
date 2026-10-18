import { expect, test, type Page } from '@playwright/test'

/**
 * Wiki 浏览路径：条目树 → 正文 → 双链跳转 → lint 定位。
 *
 * 选择器策略：
 * - 条目树是 aside[aria-label="条目目录"]，条目是 RouterLink（role=link）
 * - 正文是 role=article，四段按 h2 标题定位
 * - lint 是独立路由 /wiki/lint，问题项本身是 RouterLink，可定位回具体条目
 * 不依赖样式 class，组件调整不影响用例。
 */

async function openWiki(page: Page) {
  await page.goto('/wiki')
  // /wiki 未指定条目时自动落到目录第一条
  await expect(page.getByRole('article')).toBeVisible({ timeout: 30_000 })
  await expect(page.getByRole('complementary', { name: '条目目录' })).toBeVisible({
    timeout: 30_000,
  })
}

/** 当前正文标题 */
function docTitle(page: Page) {
  return page.getByRole('article').getByRole('heading', { level: 1 })
}

test.describe('Wiki 条目浏览', () => {
  test('条目树 → 正文 → 双链跳转', async ({ page }) => {
    await openWiki(page)

    const tree = page.getByRole('complementary', { name: '条目目录' })

    await test.step('条目树载入多条条目', async () => {
      await expect(tree.getByRole('link').first()).toBeVisible({ timeout: 30_000 })
      const count = await tree.getByRole('link').count()
      expect(count, '条目树至少要有两条条目').toBeGreaterThan(1)
    })

    await test.step('点击条目树里的另一条，正文切换过去', async () => {
      const before = (await docTitle(page).innerText()).trim()
      const links = tree.getByRole('link')
      const total = await links.count()

      // 找一条与当前不同的条目：链接文本含状态徽章，故用「不含当前标题」判断
      let targetIndex = -1
      for (let index = 0; index < total; index += 1) {
        const text = (await links.nth(index).innerText()).trim()
        if (!text.includes(before)) {
          targetIndex = index
          break
        }
      }
      expect(targetIndex, '条目树里找不到另一条可切换的条目').toBeGreaterThanOrEqual(0)

      const target = links.nth(targetIndex)
      const targetText = (await target.innerText()).trim()
      await target.click()

      await expect(page).toHaveURL(/\/wiki\/.+/)
      await expect(docTitle(page)).toBeVisible({ timeout: 30_000 })

      // 路由切换后正文由接口重取，标题要等异步渲染：用轮询等它真正变成目标条目，
      // 而不是点完立刻读一次（会读到上一条，属真实时序而非断言错误）
      await expect
        .poll(async () => (await docTitle(page).innerText()).trim(), {
          timeout: 30_000,
          message: `点击「${targetText}」后正文标题一直没变（仍是「${before}」）`,
        })
        .not.toBe(before)

      const after = (await docTitle(page).innerText()).trim()
      // 正文标题应当就是树里那一条（链接文本含状态徽章，故用包含判断）
      expect(targetText, '正文标题与点击的条目对不上').toContain(after)
      await expect(target).toHaveAttribute('aria-current', 'page')
    })

    await test.step('正文四段结构齐全', async () => {
      const article = page.getByRole('article')
      for (const section of ['摘要', '适用条件', '例外', '关联条目']) {
        await expect(
          article.getByRole('heading', { level: 2, name: new RegExp(section) }),
          `正文缺少「${section}」段落`,
        ).toBeVisible()
      }
    })

    await test.step('双链跳转到另一个条目', async () => {
      const article = page.getByRole('article')
      const relatedSection = article
        .locator('section')
        .filter({ has: page.getByRole('heading', { level: 2, name: /关联条目/ }) })
      const link = relatedSection.getByRole('link').first()

      if ((await link.count()) === 0) {
        // 该条目没有生效双链：如实跳过，并断言页面明说「没有关联条目」
        await expect(relatedSection).toContainText(/没有关联条目|待创建/)
        test.info().annotations.push({
          type: 'skip-reason',
          description: '当前条目没有可跳转的生效双链',
        })
        return
      }

      const targetTitle = (await link.innerText()).trim()
      expect(targetTitle.length).toBeGreaterThan(0)
      const beforeUrl = page.url()

      await link.click()
      await expect(page).not.toHaveURL(beforeUrl)
      await expect(page).toHaveURL(/\/wiki\/.+/)
      await expect(docTitle(page)).toContainText(targetTitle, { timeout: 30_000 })
    })
  })

  test('lint 定位：体检页给出结论并可定位到条目', async ({ page }) => {
    // 体检要调模型判定矛盾，实测 40~70 秒，这里给足时间
    test.setTimeout(420_000)

    await page.goto('/wiki/lint')
    await expect(page.getByRole('heading', { name: '条目体检', level: 2 })).toBeVisible({
      timeout: 30_000,
    })

    await test.step('四类问题各有一个计数，且有总数', async () => {
      for (const label of ['矛盾', '过期', '孤页', '待创建', '问题总数']) {
        await expect(page.getByText(label, { exact: true }).first()).toBeVisible()
      }
    })

    const rerunButton = page.getByRole('button', { name: '重新体检' })

    await test.step('触发一次真实体检', async () => {
      // 两种入口：空态里的「重新体检」按钮，或页头按钮；等到其中一个可见再点，避免空点
      const entry = rerunButton.or(page.getByRole('button', { name: '重新体检' }).last())
      await expect(entry.first()).toBeVisible({ timeout: 30_000 })
      await entry.first().click()

      // 先确认请求确实发起了，再等结论，失败时能区分「没点动」与「后端慢」
      await expect
        .poll(
          async () => {
            const busy = await page.getByText(/正在跑体检|体检中/).count()
            const done = /四类检查全部通过|条问题/.test(
              await page.locator('.lint-page').innerText(),
            )
            return busy > 0 || done
          },
          { timeout: 30_000, message: '点击后 30 秒内没有进入体检中状态' },
        )
        .toBe(true)

      // 结论二选一：问题清单，或「四类检查全部通过」
      await expect
        .poll(
          async () => /四类检查全部通过|条问题/.test(await page.locator('.lint-page').innerText()),
          { timeout: 360_000, message: '6 分钟内没有拿到体检结论' },
        )
        .toBe(true)
    })

    const pageText = await page.locator('.lint-page').innerText()
    const clean = /四类检查全部通过/.test(pageText)

    await test.step('结论明确：有问题给出分组清单，无问题写明全部通过', async () => {
      if (clean) {
        await expect(page.locator('.lint-clean')).toBeVisible()
      } else {
        await expect(page.getByRole('link', { name: /./ }).first()).toBeVisible()
      }
    })

    if (clean) {
      // 实测当前后端 72 条目、四类问题均为 0，没有可定位的问题项
      test.info().annotations.push({
        type: 'skip-reason',
        description: '本次体检四类问题均为 0，无可定位的问题项',
      })
      return
    }

    await test.step('点击问题项定位到对应条目', async () => {
      const issue = page.locator('.lint-item').first()
      await expect(issue).toBeVisible()
      await issue.click()

      await expect(page).toHaveURL(/\/wiki\/.+/)
      await expect(page.getByRole('article').getByRole('heading', { level: 1 })).toBeVisible({
        timeout: 30_000,
      })
    })
  })

  test('条目状态筛选可用', async ({ page }) => {
    await openWiki(page)
    const tree = page.getByRole('complementary', { name: '条目目录' })
    const filters = tree.getByRole('group', { name: '按条目状态筛选' })
    await expect(filters).toBeVisible({ timeout: 30_000 })

    await test.step('切到「生效」后筛选态与条目列表都更新', async () => {
      const active = filters.getByRole('button', { name: /生效/ })
      await active.click()
      await expect(active).toHaveAttribute('aria-pressed', 'true')
      await expect(tree.getByRole('link').first()).toBeVisible()
    })
  })

  test('1280 / 1080 / 760 三档无横向溢出', async ({ page }) => {
    await openWiki(page)
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
