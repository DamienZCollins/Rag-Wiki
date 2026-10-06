# RAG-Wiki

电商售后 Agent，核心是 **LLM Wiki 快通道 + RAG 慢通道**的双通道知识问答。

- 用户点开售后页 → 快通道直接返回候选答案，**零 LLM 调用、零向量检索**，毫秒级
- 用户自由提问 → 走 RAG：BM25 + 向量混合召回、RRF 融合、带引用生成，召回不足则拒答
- 配套评测体系：分层归因 + 回归门禁

## 技术栈

Java 21 · Spring Boot 3 · MyBatis-Plus · MySQL 8.4 · Redis 7.4 · Elasticsearch 8.15 · OpenAI 兼容接口 · Markdown + Git

## 快速开始

### 1. 准备本地配置

```bash
cp .env.example .env
```

`.env` 不入库。默认值都是与 `docker-compose.yml` 对齐的本地开发口令，直接可用；模型密钥留空时相关能力不可用，但不影响中间件与语料验证。

### 2. 启动中间件

```bash
bash scripts/dev.sh up
```

Windows PowerShell：

```bash
powershell -ExecutionPolicy Bypass -File scripts/dev.ps1 up
```

`up` 会等到三个服务全部 `healthy` 才返回。

其余命令（两个脚本功能一致）：

| 命令 | 说明 |
| --- | --- |
| `status` | 查看容器状态 |
| `health` | 逐项探活 MySQL / Redis / ES |
| `logs` | 跟踪日志 |
| `down` | 停止（保留数据卷） |
| `restart` | 重启并等待 healthy |
| `reset` | 删除数据卷（需二次确认，数据不可恢复） |

### 3. 初始化数据库表

```bash
docker exec -i -e MYSQL_PWD=rag_wiki rag-wiki-mysql mysql -u rag_wiki rag_wiki < sql/schema.sql
docker exec -i -e MYSQL_PWD=rag_wiki rag-wiki-mysql mysql -u rag_wiki rag_wiki < sql/seed.sql
```

两个脚本开头都有 `SET NAMES utf8mb4;`，不用额外加客户端编码参数。

### 4. 校验语料

```bash
python scripts/check-corpus.py
```

校验政策文档 frontmatter 完整性与工单 JSONL 全量解析。

### 5. 启动应用

```bash
set -a && . ./.env && set +a
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.config.additional-location=optional:file:./config/ --spring.profiles.active=local"
```

打包后运行同理：

```bash
set -a && . ./.env && set +a
java -jar target/rag-wiki-0.0.1-SNAPSHOT.jar --spring.config.additional-location=optional:file:./config/ --spring.profiles.active=local
```

验证：

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/v3/api-docs
```

Swagger UI 在 http://localhost:8080/swagger-ui/index.html

## 快通道接口

```bash
# 场景初始化：售后页直查，毫秒级，走 Redis 缓存
curl 'http://localhost:8080/api/fastpath/entries?scene=aftersale'

# 作答：传 entryId 表示用户点了候选选项，传 question 表示自由输入
curl -X POST http://localhost:8080/api/fastpath/answer -H 'Content-Type: application/json'   -d '{"question":"退款发票冲红"}'

# 从 Wiki 条目派生候选（纯规则，零模型调用）
curl -X POST http://localhost:8080/api/fastpath/entries/derive

# 上线校验并发布：来源失效的条目同步下线，没来源的候选上不了线
curl -X POST http://localhost:8080/api/fastpath/entries/publish

# 流量沉淀：把慢通道被反复问到的问题捞进候选池
curl -X POST http://localhost:8080/api/fastpath/distill
```

**快慢分流在 `POST /api/chat` 里**：Agent 先试快通道，命中就直接答（事件流里没有 `THINKING`、
没有 `TOOL_CALL`，也就没有任何模型调用），未命中才走内层循环检索生成。两条通道互斥，不会既直答又检索。

命中判定两级：规范化后完全相同的精确匹配，以及**条目关键词全部出现在问题里**的关键词匹配。
关键词还要求有区分度（至少两个，或有一个长度 ≥ 3 的关键词），否则一个「运费」就会把
「退货运费谁承担」匹配到「运费计价重量」上去。

## Wiki 编译接口

```bash
# 全量编译（离线批处理，可重复执行）
curl -X POST http://localhost:8080/api/wiki/compile -H 'Content-Type: application/json' -d '{"incremental":false}'

# 增量编译：只重算输入变化的文档，并经双链传播带出受影响的条目
curl -X POST http://localhost:8080/api/wiki/compile -H 'Content-Type: application/json' -d '{"incremental":true}'

# 只编译一篇，允许编译已失效文档（用于复现历史状态）
curl -X POST http://localhost:8080/api/wiki/compile -H 'Content-Type: application/json' -d '{"sourceDocId":"policy-freight-rule-v2024"}'

# 条目体检
curl -X POST http://localhost:8080/api/wiki/lint
curl 'http://localhost:8080/api/wiki/pages?status=ALL'
```

条目正文落在 `wiki/pages/<page_id>.md`，元数据在 `wiki_page` 表。
正文结构固定为四段：摘要 / 适用条件 / 例外 / 关联条目——结构固定才好 diff。

**应用不做自动 git 提交**：编译产物该由人看过 diff 之后再提交，应用在后台改仓库状态是意外副作用。

## 检索接口

先建索引（离线批处理，可重复执行）：

```bash
curl -X POST http://localhost:8080/api/rag/index/rebuild
```

索引源由 `rag.index.source` 决定：`doc` 读 `corpus/policies`，`wiki` 读 `wiki/pages`。
两者写的是不同的物理索引（`knowledge_doc_v1` / `knowledge_wiki_v1`），查询一律走别名
`knowledge_chunks`。**切换索引源只需要改别名指向**，不用改代码也不用重启：

```bash
curl -X POST http://localhost:9200/_aliases -H 'Content-Type: application/json' -d '{"actions":[{"remove":{"index":"knowledge_wiki_v1","alias":"knowledge_chunks"}},{"add":{"index":"knowledge_doc_v1","alias":"knowledge_chunks","is_write_index":true}}]}'
```

改动 `rag.index.source` 只影响「重建时写哪套语料、建哪个物理索引」，不影响在线检索走哪个索引。

再检索：

```bash
curl -X POST http://localhost:8080/api/rag/search \
  -H 'Content-Type: application/json' \
  -d '{"question":"退货运费谁出"}'
```

返回里同时给出两路各自的原始分与名次，以及 RRF 融合分。
`refused=true` 表示两路最高原始分都低于各自阈值，此时 `chunks` 为空。
两路都召回为空时接口按 `RAG_INDEX_EMPTY`（4001）报错——它表示索引里没有可召回文档，多半是还没重建。

拒答判定用的是**单路原始分**，不是融合分——融合分只反映名次，没有绝对量纲。
两个阈值在 `config/application.yml` 的 `rag.refuse.*`，是拿真 embedding 在语料上标定出来的
（当前语料 + Qwen3-Embedding-0.6B 下，完全无关问题的向量分上限约 0.71，范围内问题下限约 0.74）。
**换 embedding 模型、扩语料、改切分策略都要重新标定**，标定方法见 `docs/拆分任务/阶段3/阶段3-验收记录.md` 第 6 节。

## 对话接口

`POST /api/chat`，以 SSE 推送事件。
```bash
curl -N -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"sessionId":"demo-1","message":"帮我查一下订单 SO20250901001 现在到哪了"}'
```

事件名为 `STARTED` / `THINKING` / `TEXT_DELTA` / `TOOL_CALL` / `TOOL_RESULT` / `FINISHED` / `ERROR`，
载荷是事件对象的 JSON，定义见 `com.ragwiki.agent.protocol.Event`。
`FINISHED` 是唯一终止事件，`reason` 取 `COMPLETED` / `MAX_STEPS` / `CANCELLED` / `FAILED`。

⚠️ 流一旦开始推送，HTTP 状态就已经是 200，模型报错之类的问题只能在流里用 `ERROR` 事件传达。

### 没有模型密钥时怎么验

`model.chat` 三件套留空时应用照常启动，一提问会返回 `ERROR code=1001`，提示缺哪几个配置。
要完整跑通链路，可以起一个 OpenAI 兼容的桩上游替代**模型输出**：

```bash
javac -d target/stub src/test/java/com/ragwiki/support/StubModelServer.java
java -cp target/stub com.ragwiki.support.StubModelServer 8090 20
```

然后让应用指向它：

```bash
set -a && . ./.env && set +a
CHAT_BASE_URL=http://127.0.0.1:8090/v1 CHAT_API_KEY=stub CHAT_MODEL=stub-model \
java -jar target/rag-wiki-0.0.1-SNAPSHOT.jar --spring.config.additional-location=optional:file:./config/ --spring.profiles.active=local
```

桩只替换模型输出，流式协议解析、SSE 推送、工具调用、落库、恢复都由本项目代码真实执行。

## 目录结构

```text
├── .gitattributes            统一换行符为 LF，避免 clone 后脚本在 Git Bash 下失效
├── .env.example              环境变量模板：连接、端口、模型凭据
├── pom.xml                   Spring Boot 3.5 + Java 21
├── docker-compose.yml        本地中间件：MySQL / Redis / ES
├── config/
│   ├── application.yml       Spring Boot 配置模板 + 全部调优参数默认值，全 ${VAR:default}，入库
│   └── application-local.yml.example  个人密钥覆盖示例，入库
├── sql/
│   ├── schema.sql            建表脚本，可重复执行
│   └── seed.sql              演示订单数据（query_order 工具的数据源）
├── src/main/java/com/ragwiki/
│   ├── RagWikiApplication.java
│   ├── common/               公共层：统一返回体、错误码、全局异常、模型客户端、Markdown 解析
│   ├── config/               装配层：数据源、MyBatis-Plus、ES、OpenAPI 等 Bean 装配
│   ├── agent/                Agent 内核：协议、主循环、工具、会话与事件存储
│   ├── wiki/                 LLM Wiki：编译、双链、增量、失效检测、lint、快通道
│   ├── rag/                  RAG：切分、索引同步、双路召回、RRF、拒答
│   └── eval/                 评测：用例、runner、指标、分层归因、回归门禁
├── corpus/                   语料（只读不可变）
│   ├── policies/             售后政策文档，Markdown + YAML frontmatter
│   ├── tickets/              历史工单，JSONL
│   └── README.md             格式说明与刻意埋设的验证点
├── wiki/
│   └── pages/                Wiki 编译产物，一个条目一个 Markdown 文件
└── scripts/
    ├── dev.sh                中间件管理（bash）
    ├── dev.ps1               中间件管理（PowerShell，需保持 UTF-8 BOM）
    └── check-corpus.py       语料校验
```

依赖方向：`agent / wiki / rag / eval` 只依赖 `common`，`config` 只做装配。业务模块之间不互相引用内部类，需要协作走对方 `api` 包暴露的接口。

`corpus/README.md` 里登记了几个**刻意埋设**的验证点（版本冲突、跨文档重叠、孤页候选），不要当作脏数据清理。

## 配置分层

取值优先级：**环境变量 > `config/application-local.yml` > `config/application.yml` 默认值**。

```text
.env                                  本地变量与密钥，gitignore
config/application.yml                统一模板，只写 ${VAR:default}，入库
config/application-local.yml          个人覆盖，gitignore
config/application-local.yml.example  覆盖示例，入库
```

**`.env` 只放跟本机环境绑定的值**：中间件连接、端口、模型凭据。
检索参数、切分参数、拒答阈值、Agent 步数这些调优值只写在 `config/application.yml` 里一处。

这不是洁癖：调优参数如果在两处各写一份默认值，必然漂移——改了一处以为生效，
实际被另一处盖住，排查起来很费时间（阶段 3 标定拒答阈值时就踩过一次）。
需要临时覆盖时，把 `application.yml` 里 `${}` 中的变量名设进环境即可。

应用启动时从仓库根的 `config/` 目录额外加载配置：

```bash
java -jar app.jar --spring.config.additional-location=optional:file:./config/ --spring.profiles.active=local
```

模型接入拆成三组独立配置，可分别换模型、分别限额、分别排查：

| 配置组 | 用途 | 环境变量前缀 |
| --- | --- | --- |
| `model.chat` | 在线问答主链路 | `CHAT_` |
| `model.embedding` | RAG 向量化，索引与查询必须同模型 | `EMBEDDING_` |
| `model.compile` | Wiki 离线编译 | `COMPILE_` |

⚠️ `EMBEDDING_DIMENSIONS` 必须与 ES 索引 `dense_vector` 的 `dims` 一致，改动即需重建索引。
应用启动时会校验，不一致直接启动失败。

## 本地服务地址

| 服务 | 地址 |
| --- | --- |
| MySQL | `localhost:3306`，库 `rag_wiki`，应用账号 `rag_wiki` |
| Redis | `localhost:6379` |
| Elasticsearch | http://localhost:9200 |
| 应用 | `localhost:8080` |

端口与账号全部可用 `.env` 覆盖，默认值仅用于本地开发。

## 环境说明

- ES 关闭了安全认证（`xpack.security.enabled=false`）且为单节点，**仅供本地开发，禁止照搬上线**
- ES 堆内存默认 512m，语料规模上来后调整 `ES_JAVA_OPTS`
- ES 官方镜像**不含**中文分词插件，检索用内置的 `cjk`（bigram）分析器
- 本地为固定开发凭据，真实环境必须全部替换并启用 ES 认证
- 数据库连接不在应用启动时校验：HikariCP 首次取连接才建池，库不可用时应用仍会启动，状态由 `/actuator/health` 暴露（`db: DOWN`，HTTP 503）
- **跑 `mvn test` 需要中间件已启动**：集成测试跑的是真实 MySQL 与真实 HTTP，不是内存替身。测试通过 surefire 的 `spring.config.additional-location` 直接加载仓库根的 `config/application.yml`，不另外维护一份测试配置

## 阶段进度

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 0 | 仓库与环境：中间件编排、配置规范、语料 | 已完成 |
| 1 | Spring Boot 骨架 | 已完成 |
| 2 | Agent 最小闭环（主循环 / SSE / 工具调用） | 已完成 |
| 3 | RAG 基础闭环（混合召回 / RRF / 引用 / 拒答） | 已完成 |
| 4 | LLM Wiki 编译（归并 / 双链 / 增量 / 失效 / lint） | 已完成 |
| 5 | 快通道与快慢分流 | 已完成 |
| 6 | 评测体系（分层归因 / 对比实验 / 回归门禁） | 未开始 |
| 7 | 收尾与面试表达 | 未开始 |
| 8 | 演示界面与设计沉淀 | 未开始 |
