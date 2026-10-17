# corpus

售后语料，只读不可变。政策修订靠新增版本文件，不原地修改已有文件；工单只追加，不回头改历史。

## 目录

```text
corpus
├── policies/                       售后政策文档（Markdown + YAML frontmatter）
│   └── _archived/                  已失效的历史版本
├── tickets/                        历史工单（JSONL，按季度分文件）
└── README.md
```

## 政策文档格式

文件名 `policy-<主题>.md`，历史版本放 `_archived/` 并在文件名带版本后缀。

```markdown
---
doc_id: policy-freight-rule-v2025     # 唯一标识，等于 source_doc.doc_id
title: 运费收取与承担规则
category: 运费                          # 10 个类目之一
version: v2025.1                      # 同一 doc_id 的版本号
effective_at: 2025-01-01              # 生效日期
status: active                        # active / retired
supersedes: policy-freight-rule-v2024 # 被本版本取代的 doc_id，无则留空
related:                              # 相关知识条目，供编译期建双链与跨文档重叠检测
  - policy-freight-insurance
---

正文用 Markdown，二级标题划分主题，表格整块保留。
```

`_archived/` 下的失效文档额外带 `retired_at` 与 `superseded_by` 两个字段。

### 类目

退换货、运费、退款、质量、物流、大件、生鲜、发票、价保、会员、兜底。

## 工单格式

JSONL，一行一条，按季度分文件（`2025Q1.jsonl` / `2025Q2.jsonl`）。

```json
{"ticket_id":"T20250105-0001","created_at":"2025-01-05T10:12:00+08:00","channel":"app","category":"运费","question":"退货的邮费到底谁掏？","answer":"看退货原因……","related_doc":"policy-freight-rule-v2025","resolution":"已解决"}
```

| 字段 | 说明 |
| --- | --- |
| `ticket_id` | 工单号，`T<日期>-<序号>` |
| `created_at` | 创建时间，`Asia/Shanghai` |
| `channel` | 来源渠道：`app` / `web` / `phone` |
| `category` | 同政策类目 |
| `question` | 买家原话，刻意保留口语化表达 |
| `answer` | 当时的客服答复 |
| `related_doc` | **正确答案出处**，指向 `doc_id`。阶段 6 的评测用例优先复用工单的 `related_doc` 标注 |
| `resolution` | 处理结果 |

`question` 用口语化问法，是阶段 3 验证「关键词问法与口语化问法召回同一批片段」的输入来源。

## 刻意埋设的验证点

以下内容是**刻意设计**的，不要当作脏数据清理。改动语料前先确认不会破坏这些验证点。

### 1. 版本冲突：运费补贴上限 30 元 vs 20 元

- `_archived/policy-freight-rule-v2024.md`：质量问题退货运费补贴上限 **30 元**，包邮门槛 79 元
- `policies/policy-freight-rule-v2025.md`：同一口径为 **20 元**，包邮门槛 99 元

v2025 的 `supersedes` 指向 v2024，用于验证：

- 阶段 4 失效检测：新政策生效后旧条目转为已失效
- 阶段 4 lint：矛盾检测能报出同概念的不同结论
- 阶段 4 检索：已失效文档不出现在召回结果中
- 阶段 6 对抗样本：问「运费最多补多少」，模型必须给现行口径 20 元，不能给 30 元

### 2. 跨文档重叠：运费规则 / 运费险 / 会员运费券

三篇政策都在讲「退货的运费谁承担、怎么抵扣」，描述范围重叠：

- `policy-freight-rule-v2025.md`：承担方划分 + 平台补贴上限 20 元
- `policy-freight-insurance.md`：运费险理赔上限 25 元，与平台补贴不叠加
- `policy-member-freight-coupon.md`：会员运费券面额，与平台补贴不可同时使用
- `policy-member-benefit.md`：会员退货运费补贴上限表

用于验证：

- 阶段 4 双链生成：四篇应通过 `related` 互链，而不是各自独立成页
- 阶段 4 归并：同一概念「退货谁承担运费」应归并到条目而不是重复新建
- 阶段 6 对抗样本：口径相近但归属不同的问法要能区分

### 3. 跨文档冲突：会员退货运费补贴上限 30 元 vs 基础规则 20 元

- `policy-freight-rule-v2025.md`：单笔订单最高补贴 **20 元**
- `policy-member-benefit.md`：金卡及以上会员质量问题退货运费补贴上限 **30 元**

两篇都是 `active`，且**刻意没有互相调和**。这是阶段 4 lint 的「矛盾检测」活样本：同一概念在不同条目中给出冲突结论，lint 必须能报出来并指向这两条。

（现实中这是会员权益和基础规则没对齐的典型情况，不是笔误。）

### 4. 被新版删掉的规则（用于验证失效检测）

`_archived/policy-freight-rule-v2024.md` 里的「运费补贴申领方式」一节（买家需在 7 个自然日内手动提交申领）
是 2024 版特有的规则，2025 版改成了自动补贴，这一条被删掉了。

用于验证：

- 阶段 4 失效检测：只依赖 2024 版的条目，在 2025 版编译后状态变为已失效
- 与其他条目区分开：被新版重新确立的概念（如「运费补贴上限」）应当被**更新**而不是失效，
  这正是「归并」与「失效」两条规则的边界

### 5. 孤页候选

`policy-invoice.md` 的 `related` 为空，且只有 `policy-refund-timeline.md`、`policy-price-protection.md` 指向它。若阶段 4 的 lint 判定它双向链接不足，属于预期结果，用于验证孤页检测能跑通。

## 已知缺口

**过期检测（阶段 4 lint 的「生效期已过但状态仍为已生效」）在语料中没有现成样本。**
现有 14 篇都是长期有效的政策，没有天然带截止日的。阶段 4 实现 lint 时，用测试夹具构造一条 `expires_at` 已在过去、`status` 仍为 `active` 的条目来验证，不为了凑验证点往语料里塞不真实的文件。

## 统计

| 项目 | 数量 |
| --- | --- |
| 政策文档（active） | 13 |
| 政策文档（retired，在 `_archived/`） | 1 |
| 历史工单 | 60（`2025Q1.jsonl` 30 条 + `2025Q2.jsonl` 30 条） |
| 覆盖类目 | 10（退换货、运费、退款、质量、物流、大件、生鲜、发票、价保、会员）|
