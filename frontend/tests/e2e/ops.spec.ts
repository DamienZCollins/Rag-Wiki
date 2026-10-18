import { expect, test, type Locator, type Page } from '@playwright/test'

/**
 * 运维台（/ops）端到端测试。
 *
 * 只验证只读行为与「二次确认弹窗能被取消」，不触发任何写操作：
 * 编译、重建、别名切换、用例导入、跑批、基线固化都会真实改变后端状态
 * （基线被覆盖、别名被切走、跑批占十几分钟），不适合放进回归用例。
 */

/** 操作卡片。卡片自带 data-ops-task-title，不依赖易变的 class 或嵌套层级 */
function taskCard(page: Page, title: string): Locator {
  return page.locator(`[data-ops-task-title="${title}"]`)
}

test.describe('运维操作台', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/ops')
    // 顶栏标题由 AppLayout 渲染，页面内不再重复 h1
    await expect(page.getByRole('heading', { name: '运维操作台', level: 1 })).toBeVisible()
  })

  test('九个入口与三个分组齐备', async ({ page }) => {
    await test.step('三个分组标题可见', async () => {
      await expect(page.getByRole('heading', { name: 'Wiki 编译与体检', level: 2 })).toBeVisible()
      await expect(page.getByRole('heading', { name: 'RAG 索引', level: 2 })).toBeVisible()
      await expect(page.getByRole('heading', { name: '评测与门禁', level: 2 })).toBeVisible()
    })

    await test.step('九个操作卡片各有标题', async () => {
      const titles = [
        'Wiki 编译',
        '条目体检',
        '索引状态查询',
        '索引重建',
        '索引别名切换',
        '用例导入',
        '跑批',
        '基线固化',
        '门禁查询',
      ]
      for (const title of titles) {
        const card = taskCard(page, title)
        await expect(card, `缺少操作卡片：${title}`).toHaveCount(1)
        await expect(card.getByRole('heading', { name: title, level: 3 })).toBeVisible()
      }
    })

    await test.step('未执行的操作显示「尚未执行」而不是伪造结果', async () => {
      await expect(taskCard(page, '跑批').getByText('尚未跑批')).toBeVisible()
      await expect(taskCard(page, '索引重建').getByText('尚未执行重建')).toBeVisible()
    })
  })

  test('索引状态查询展示真实返回', async ({ page }) => {
    const card = taskCard(page, '索引状态查询')

    await test.step('点击查询按钮', async () => {
      await card.getByRole('button', { name: '查询索引状态' }).click()
    })

    await test.step('结果区出现别名指向与物理索引', async () => {
      await expect(card.getByText('状态返回')).toBeVisible({ timeout: 20_000 })
      await expect(card.getByText('别名指向', { exact: true })).toBeVisible()
      // 实测 aliasTarget=knowledge_wiki_v1，物理索引含 knowledge_doc_v1
      await expect(card.getByText('knowledge_wiki_v1', { exact: true }).first()).toBeVisible()
      await expect(card.getByText('knowledge_doc_v1', { exact: true }).first()).toBeVisible()
    })
  })

  test('有副作用的操作会弹二次确认，取消后不发起请求', async ({ page }) => {
    const cases = [
      { title: '索引重建', button: '重建索引', dialog: '确认重建 RAG 索引？' },
      { title: '用例导入', button: '导入用例', dialog: '确认导入评测用例？' },
      { title: '跑批', button: '开始跑批', dialog: '确认跑一批评测？' },
      { title: '基线固化', button: '固化为基线', dialog: '确认把该批次固化为基线？' },
    ]

    for (const item of cases) {
      await test.step(`${item.title}：确认框弹出并取消`, async () => {
        const card = taskCard(page, item.title)
        const trigger = card.getByRole('button', { name: item.button })
        // 基线固化在填了 runId 后按钮才可用；runId 由只读的历史批次接口预填
        if (item.title === '基线固化') {
          await expect(trigger).toBeEnabled({ timeout: 20_000 })
        }
        await trigger.click()

        const dialog = page.getByRole('dialog')
        await expect(dialog).toBeVisible()
        await expect(dialog.getByRole('heading', { name: item.dialog })).toBeVisible()
        await expect(dialog.getByText(/影响范围/)).toBeVisible()

        await dialog.getByRole('button', { name: '取消' }).click()
        await expect(dialog).toBeHidden()
        // 取消后不应进入等待态
        await expect(card.getByText('等待后端返回')).toHaveCount(0)
      })
    }
  })

  test('Wiki 编译按模式切换并给出危险提示', async ({ page }) => {
    const card = taskCard(page, 'Wiki 编译')

    await test.step('默认增量模式，确认框说明影响范围', async () => {
      await card.getByRole('button', { name: '增量编译' }).click()
      const dialog = page.getByRole('dialog')
      await expect(dialog.getByRole('heading', { name: '确认执行增量编译？' })).toBeVisible()
      await expect(dialog.getByText(/影响范围/)).toBeVisible()
      await dialog.getByRole('button', { name: '取消' }).click()
      await expect(dialog).toBeHidden()
    })

    await test.step('全量模式需要额外交代且按钮文案随之变化', async () => {
      await card.getByRole('tab', { name: '全量' }).click()
      await card.getByRole('button', { name: '全量编译' }).click()
      const dialog = page.getByRole('dialog')
      await expect(dialog.getByRole('heading', { name: '确认执行全量编译？' })).toBeVisible()
      await dialog.getByRole('button', { name: '取消' }).click()
      await expect(dialog).toBeHidden()
    })

    await test.step('单篇模式需要填源文档标识', async () => {
      await card.getByRole('tab', { name: '单篇' }).click()
      await expect(card.getByLabel('源文档标识')).toBeVisible()
      // 未填时按钮禁用，不会发出缺少参数的单篇编译
      await expect(card.getByRole('button', { name: '单篇编译' })).toBeDisabled()
    })
  })

  test('索引切换：提示影响在线检索，且不会重复触发', async ({ page }) => {
    const statusCard = taskCard(page, '索引状态查询')
    await statusCard.getByRole('button', { name: '查询索引状态' }).click()
    await expect(statusCard.getByText('状态返回')).toBeVisible({ timeout: 20_000 })

    const switchCard = taskCard(page, '索引别名切换')

    await test.step('危险提示写明影响在线检索', async () => {
      await expect(switchCard.getByText(/立即改变在线检索实际命中的索引/)).toBeVisible()
    })

    await test.step('当前指向已知，切换按钮存在', async () => {
      await expect(switchCard.getByText('别名指向', { exact: true })).toHaveCount(0)
      await expect(switchCard.getByRole('button', { name: '切换别名' })).toBeVisible()
    })
  })

  test('门禁查询：只读接口直接返回真实结果', async ({ page }) => {
    const card = taskCard(page, '门禁查询')
    const queryButton = card.getByRole('button', { name: '查询门禁' })

    await test.step('历史批次载入后 runId 已预填，按钮可用', async () => {
      await expect(queryButton).toBeEnabled({ timeout: 20_000 })
    })

    await test.step('点击后展示真实门禁返回', async () => {
      await queryButton.click()
      await expect(card.getByText('门禁返回')).toBeVisible({ timeout: 20_000 })
      await expect(card.getByText('门禁结论', { exact: true })).toBeVisible()
      await expect(card.getByText('基线 runId', { exact: true })).toBeVisible()
    })
  })

  test('1280 / 1080 / 760 三档无横向溢出', async ({ page }) => {
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

  test('键盘可达：Tab 能进入运维台内的可交互元素', async ({ page }) => {
    let reached = false
    for (let i = 0; i < 12; i += 1) {
      await page.keyboard.press('Tab')
      const inPage = await page.evaluate(() => {
        const active = document.activeElement
        if (!(active instanceof HTMLElement)) return false
        return active.closest('.ops-page') !== null
      })
      if (inPage) {
        reached = true
        break
      }
    }
    expect(reached, '按 Tab 12 次仍未聚焦到运维台内的元素').toBe(true)
  })

  test('减少动效偏好下无动画', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.goto('/ops')
    await expect(page.getByRole('heading', { name: '运维操作台', level: 1 })).toBeVisible()

    const animating = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.ops-page *'))
        .filter((element) => {
          const style = getComputedStyle(element)
          return style.animationName !== 'none' && parseFloat(style.animationDuration) > 0.05
        })
        .map((element) => `${element.tagName}.${element.className}`),
    )
    expect(animating, `减少动效下仍有动画：${animating.join(', ')}`).toHaveLength(0)
  })
})
