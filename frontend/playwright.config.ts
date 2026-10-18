import { defineConfig, devices } from '@playwright/test'

/**
 * 端到端测试配置。
 *
 * 开发服务器由外部守护脚本（scripts/dev-watch.ps1）维护，这里不配 webServer：
 * 自动拉起会与已在 5173 监听的 vite 抢端口。
 * 本机装有 Chrome，统一走 channel: 'chrome'，不下载 Playwright 自带浏览器。
 * baseURL 可用 E2E_BASE_URL 覆盖，便于指向其他端口或部署实例。
 *
 * 落盘：frontend/test-results、frontend/playwright-report（均在 .gitignore 中）。
 * 产物只保留失败截图，不录 trace/video：本机开发目录会被其他守护脚本清理，
 * 录制文件在收尾时读写竞争会抛出 ENOENT，反而掩盖真正的断言失败。
 */
const baseURL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173'

export default defineConfig({
  testDir: './tests/e2e',
  // 用例之间不共享状态：每条独立打开页面，可重复执行
  fullyParallel: false,
  workers: 1,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL,
    headless: true,
    channel: 'chrome',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    trace: 'off',
    screenshot: 'only-on-failure',
    video: 'off',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
  },
  projects: [
    {
      name: 'chrome',
      use: {
        ...devices['Desktop Chrome'],
        channel: 'chrome',
        viewport: { width: 1280, height: 900 },
      },
    },
  ],
})
