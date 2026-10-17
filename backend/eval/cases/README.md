# eval/cases 用例集说明

评测用例的唯一事实源。`eval_case` 表只是导入快照，改用例要改这里再重新导入。

## 文件

| 文件 | 类型 | 条数 | 要求下限 |
| --- | --- | --- | --- |
| `policy.jsonl` | 政策问答 | 54 | 40 |
| `order.jsonl` | 订单查询 | 22 | 20 |
| `refusal.jsonl` | 拒答 | 22 | 20 |
| `adversarial.jsonl` | 对抗样本 | 24 | 20 |
| 合计 | | 122 | 120 |

## 字段

```jsonc
{
  "case_id": "policy-001",              // 全局唯一，四类各自前缀编号
  "type": "POLICY",                     // POLICY / ORDER / REFUSAL / ADVERSARIAL
  "question": "退货的邮费谁掏？",          // 送进链路的问题，口语化优先
  "expected_answer_points": ["..."],     // 答案要点，逐条判定是否出现，不要求逐字
  "expected_sources": ["退货运费承担责任划分"],  // 期望命中的 Wiki 条目标题
  "expected_doc_ids": ["policy-freight-rule-v2025"],  // 期望命中的来源文档
  "expect_refusal": false,               // 是否应拒答
  "expect_tools": ["search_knowledge"],  // 期望触发的工具
  "expected_tool_args": {"orderNo": "SO20250901001"},  // 工具参数期望，仅订单类
  "forbidden_points": ["30 元"],          // 出现即判错（对抗样本用）
  "attribution_layer": "RETRIEVAL",      // 该用例失败时优先怀疑的层，仅作人工核对参考
  "note": "工单 T20250105-0002 的问法"    // 来源与设计意图
}
```

## 约定

- `expected_sources` 用 Wiki 条目标题，与 `wiki_page.title` 一致
- `expected_doc_ids` 用原始文档 `doc_id`，工单 `related_doc` 的取值可直接复用
- `expected_answer_points` 是**答案要点**不是全文，判定用包含匹配，一条要点命中即算
- 对抗样本的 `forbidden_points` 是必须不出现的旧口径或错配结论
- `attribution_layer` 只写**期望的归因层**，实际归因由 runner 按链路结果判定，两者不一致时以 runner 为准

## 期望来源的来源

`policy.jsonl` 里带 `note` 标注「工单 Txxx」的用例，期望来源复用该工单的 `related_doc` 标注，
不是人工臆断。其余用例的期望来源由政策原文逐条核对得出。

## 版本口径

语料里两处刻意埋设的版本冲突（30 元 vs 20 元、包邮 79 元 vs 99 元）在对抗样本里成对出现，
问法必须让模型给出**现行**口径。
