# frontend

RAG-Wiki 前端。Vite + Vue 3 + TypeScript + Pinia + vue-router，无 UI 组件库、无图表库。
后端位于 `backend/`，前后端只通过 HTTP 接口协作。

## 启动

```bash
pnpm install
pnpm dev          # http://127.0.0.1:5173
```

开发期 `/api` 与 `/actuator` 由 Vite 代理到 `VITE_API_BASE_URL`（默认 `http://localhost:8080`），
前端一律请求同源相对路径，避免跨域与 SSE 预检。
构建时必须显式配置 `VITE_API_BASE_URL`，否则启动即报错，不静默退化成空地址。

## 质量门

```bash
pnpm typecheck    # vue-tsc --noEmit -p tsconfig.app.json
pnpm lint         # eslint . --max-warnings 0
pnpm build        # 类型检查 + vite build
pnpm test:e2e     # playwright test（需后端与 dev server 已启动）
```

**`typecheck` 必须带 `-p tsconfig.app.json`**。根 `tsconfig.json` 是 project references 形式
（`files: []`），直接跑 `vue-tsc --noEmit` 只检查那个空配置，对 `src/` 一个文件都不检查、
永远 exit 0。这个坑开发中期踩过，全队一度误以为类型是干净的。

e2e 前置条件：后端跑在 8080 且中间件（MySQL / Redis / ES）healthy，dev server 跑在 5173。
不配 `webServer`，避免与已监听的实例抢端口。用本机 Chrome（`channel: 'chrome'`），不下载 Playwright 浏览器。
`trace` 关掉：Windows 上 trace 文件收尾时读写竞争会抛 `EPERM`/`ENOENT`，掩盖真正的断言失败。

## 目录

```text
src/
├── api/            # 接口函数与 DTO 类型（按后端模块划分）
├── components/     # 可复用展示组件，不依赖具体业务字段
├── composables/    # Toast 等跨页面轻量逻辑
├── features/       # chat / wiki / eval / ops 业务模块
├── layouts/        # 三视图共用外壳
├── pages/          # 路由页面
├── router/         # 路由表与参数解码
├── sse/            # text/event-stream 解析
├── stores/         # Pinia：主题、本地会话
└── styles/         # tokens.css / base.css / index.css
```

## 几个必须知道的契约

| 事项 | 事实 |
| --- | --- |
| 成功码 | `200`，不是 `0`（`ResultCode.SUCCESS(200, 200)`） |
| SSE | `POST /api/chat` 需要请求体，**不能用 `EventSource`**，用 fetch + ReadableStream |
| 终止信号 | `FINISHED` 是唯一终止；`ERROR` 只是流内提示，不终止 |
| 会话繁忙 | **不是 HTTP 409**：后端在已建立的流里发 `ERROR(code="2002")` + `FINISHED(FAILED)`，HTTP 始终 200 |
| 快通道命中 | 事件流里没有 `THINKING` 也没有 `TOOL_CALL`，`tokenIn/tokenOut` 为 0 |
| 拒答 | `refused=true` 时 `chunks` 为空；判定是「两路最高原始分**都**低于阈值」 |
| 索引未就绪 | `4001`（HTTP 503），**与拒答是两回事**，不能渲染成拒答 |
| `pageId` | 含中文，路由与请求都要 `encodeURIComponent` |
| 五层归因 | `COMPILE` / `RECALL` / `RERANK` / `GENERATION` / `FALLBACK`，不是四项 |

## 设计

沿用原型的纸张墨色方向，令牌全部在 `src/styles/tokens.css`，明暗两套同名变量，
切换只改根元素 `data-theme`，**组件内不写死色值**。断点固定 1280 / 1080 / 760，不新增。
状态不以颜色为唯一表达：拒答、失效、超时、失败都同时给文字。

## 文档（本地，不进版本库）

设计文档已收敛到仓库根 `docs/`，前端文档在 `docs/frontend/`，不再放在 `frontend/` 下：

```text
docs/frontend/
├── 总体方案.md             范围、技术选型、信息架构、数据流
├── 开发路径.md             阶段 0–6 的目标与验收
├── 前端设计/
│   ├── 静态原型.html       视觉与交互母版
│   └── 静态原型预览.png
└── 拆分任务/
    ├── 阶段0/              任务拆分 + 接口对齐 + 原型差异清单
    ├── 阶段1/              任务拆分 + 阶段1-验收记录
    ├── 阶段2/              任务拆分
    ├── 阶段3/              任务拆分
    ├── 阶段2+3/            阶段2+3-验收记录
    ├── 阶段4/              任务拆分 + 阶段4-验收记录
    ├── 阶段5/              任务拆分 + 阶段5-验收记录
    └── 阶段6/              任务拆分 + 阶段6-验收记录
```

公共文档在仓库根 `docs/`：`docs/接口文档.md`、`docs/评测报告.md`、`docs/评测指标口径.md`；
后端文档在 `docs/backend/`。跨端引用一律写仓库根相对路径，不用 `../`。
