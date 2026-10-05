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
```

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

## 目录结构

```text
├── .gitattributes            统一换行符为 LF，避免 clone 后脚本在 Git Bash 下失效
├── .env.example              环境变量模板，含三组模型配置
├── pom.xml                   Spring Boot 3.5 + Java 21
├── docker-compose.yml        本地中间件：MySQL / Redis / ES
├── config/
│   ├── application.yml       Spring Boot 配置模板，全 ${VAR:default}，入库
│   └── application-local.yml.example  个人密钥覆盖示例，入库
├── sql/
│   └── schema.sql            建表脚本，可重复执行
├── src/main/java/com/ragwiki/
│   ├── RagWikiApplication.java
│   ├── common/               公共层：统一返回体、错误码、全局异常、模型客户端
│   ├── config/               装配层：数据源、MyBatis-Plus、OpenAPI 等 Bean 装配
│   ├── agent/                Agent 内核：协议、主循环、工具、会话与事件存储
│   ├── wiki/                 LLM Wiki：编译、双链、增量、失效检测、lint、快通道
│   ├── rag/                  RAG：切分、索引同步、双路召回、RRF、拒答
│   └── eval/                 评测：用例、runner、指标、分层归因、回归门禁
├── corpus/                   语料（只读不可变）
│   ├── policies/             售后政策文档，Markdown + YAML frontmatter
│   ├── tickets/              历史工单，JSONL
│   └── README.md             格式说明与刻意埋设的验证点
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

## 阶段进度

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 0 | 仓库与环境：中间件编排、配置规范、语料 | 已完成 |
| 1 | Spring Boot 骨架 | 已完成 |
| 2 | Agent 最小闭环（主循环 / SSE / 工具调用） | 未开始 |
| 3 | RAG 基础闭环（混合召回 / RRF / 引用 / 拒答） | 未开始 |
| 4 | LLM Wiki 编译（归并 / 双链 / 增量 / 失效 / lint） | 未开始 |
| 5 | 快通道与快慢分流 | 未开始 |
| 6 | 评测体系（分层归因 / 对比实验 / 回归门禁） | 未开始 |
| 7 | 收尾与面试表达 | 未开始 |
| 8 | 演示界面与设计沉淀 | 未开始 |
