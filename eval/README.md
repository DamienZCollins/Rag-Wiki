# eval 目录说明

评测产物。用例源文件与基线入库，逐条结果与报告按需生成。

```text
eval
├── cases/                   用例源文件（唯一事实源）
│   ├── policy.jsonl         政策问答 54 条
│   ├── order.jsonl          订单查询 22 条
│   ├── refusal.jsonl        拒答 22 条
│   ├── adversarial.jsonl    对抗样本 24 条
│   └── README.md            用例 schema 与约定
├── baseline.json            回归门禁的对照基线（比率型指标）
├── reports/                 各批次对比数据
└── README.md                本文件
```

## 代码位置

runner 的代码在 `src/main/java/com/ragwiki/eval/`，不放在这里：
Maven 的编译范围是 `src/`，Java 文件放在 `eval/` 下不会被编译。
这个目录只放数据产物。

| 类 | 职责 |
| --- | --- |
| `runner/EvalRunner` | 批量执行用例，逐条判定与归因，汇总落库 |
| `runner/Attributor` | 分层归因，失败用例归到唯一层 |
| `runner/Aggregator` | 汇总指标计算 |
| `runner/Metrics` | 分位数与比率的算法口径 |
| `runner/AnswerJudge` | 答案判定：要点命中、禁点、拒答识别、引用越界 |
| `runner/CaseImporter` | 把 `cases/*.jsonl` 导入 `eval_case` 表 |
| `runner/RegressionGate` | 回归门禁：基线对比与容差判定 |

## 执行方式

```bash
# 1. 导入用例（改了 cases/*.jsonl 之后必须重新导入）
curl -X POST http://localhost:8080/api/eval/cases/import

# 2. 跑一批（同步阻塞，全量 122 条实测约 11-18 分钟，取决于模型服务当时的快慢）
curl -X POST http://localhost:8080/api/eval/run \
  -H 'Content-Type: application/json' \
  -d '{"label":"wiki 索引 + 双通道","caseTypes":[]}'

# 3. 看报告
curl http://localhost:8080/api/eval/report/<runId>

# 4. 固化为基线
curl -X POST http://localhost:8080/api/eval/baseline/<runId>

# 5. 门禁：拿基线比某一批
curl http://localhost:8080/api/eval/gate/<runId>

# 6. 中断了接着跑（每条用例跑完即落库，续跑只补没跑的）
curl -X POST http://localhost:8080/api/eval/resume/<runId>
```

## 索引源对照实验

别名指向决定在线检索走哪套索引，探针与问答都走别名：

```bash
# 看当前指向
curl http://localhost:8080/api/eval/index/status

# 切到原始文档索引
curl -X POST http://localhost:8080/api/eval/index/switch \
  -H 'Content-Type: application/json' -d '{"physicalIndex":"knowledge_doc_v1"}'

# 切回 Wiki 索引
curl -X POST http://localhost:8080/api/eval/index/switch \
  -H 'Content-Type: application/json' -d '{"physicalIndex":"knowledge_wiki_v1"}'
```

## 双通道对照实验

纯 RAG 对照组用配置开关，不用改代码：

```bash
AGENT_FAST_PATH_ENABLED=false 启动应用
```

## 注意事项

- 评测会话为 `eval-<runId>-<caseId>`，带批次号是为了重跑同一批时不接上上一批的对话历史
- 跑批中断时批次状态为 `INTERRUPTED`，已跑完的逐条结果留在 `eval_result` 里，续跑从断点接着跑
- 跑批前确认 `knowledge_doc_v1` 与 `knowledge_wiki_v1` 都已建好，否则对照实验缺一边
- 每批的配置快照（模型、索引、阈值）在 `eval_run.config` 里，跨批次比较前先核对
- **每批开跑前会自动把订单夹具复位到 `sql/seed.sql` 的基线**：订单状态是被工具改写的可变数据，
  不复位的话上一批 `refund` 改过的订单会让这一批查到与标注不符的状态，批次差异里会混进「夹具漂移」
- **跨批次比通过率要谨慎**：同配置重跑之间就有约 5% 的用例翻转。真正的结论要落在召回率、
  快通道命中集这类跨批次稳定的指标上，理由见 `docs/评测报告.md` 第 2 节
