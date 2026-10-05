-- RAG-Wiki 建表脚本
-- 可在空库上重复执行：全部使用 CREATE TABLE IF NOT EXISTS
-- 时间字段统一 datetime(3)，写入 Asia/Shanghai 墙上时间（连接参数已带 serverTimezone=Asia/Shanghai）
--
-- 执行方式：
--   docker exec -i -e MYSQL_PWD=rag_wiki rag-wiki-mysql mysql -u rag_wiki rag_wiki < sql/schema.sql
--
-- 注意：IF NOT EXISTS 只保证重复执行不报错，不做结构变更。
-- 已存在的表需要改结构时，本地直接 DROP 后重跑，或手写 ALTER。

-- mysql 客户端在容器里默认按 latin1 解释输入，中文会被二次编码写坏。
-- 显式声明，避免依赖调用方记得加 --default-character-set
SET NAMES utf8mb4;

-- ---------- 会话 ----------
CREATE TABLE IF NOT EXISTS conversation
(
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    session_id VARCHAR(64)  NOT NULL COMMENT '会话标识，对外唯一',
    user_id    VARCHAR(64)  NULL COMMENT '用户标识',
    title      VARCHAR(200) NULL COMMENT '会话标题，取自首轮用户输入',
    status     VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '会话状态：ACTIVE 进行中 / RESOLVED 已解决 / TRANSFERRED 已转人工',
    created_at DATETIME(3)  NOT NULL COMMENT '创建时间',
    updated_at DATETIME(3)  NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_session_id (session_id),
    KEY idx_conversation_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='会话';

-- ---------- 会话消息 ----------
-- 面向展示的投影，不参与会话恢复；会话恢复走 agent_event 事件回放
CREATE TABLE IF NOT EXISTS message
(
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    conversation_id BIGINT      NOT NULL COMMENT '会话主键',
    seq             INT         NOT NULL COMMENT '会话内序号，从 1 起连续递增',
    role            VARCHAR(16) NOT NULL COMMENT '消息角色：USER 用户 / ASSISTANT 模型回复 / TOOL 工具结果',
    content         MEDIUMTEXT  NULL COMMENT '文本内容',
    tool_calls      JSON        NULL COMMENT '模型请求的工具调用，仅 ASSISTANT 消息有值',
    tool_call_id    VARCHAR(64) NULL COMMENT '工具结果对应的调用标识，仅 TOOL 消息有值',
    created_at      DATETIME(3) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_conversation_seq (conversation_id, seq)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='会话消息';

-- ---------- 内核事件 ----------
-- append-only，只插入不更新，是会话恢复的事实来源
CREATE TABLE IF NOT EXISTS agent_event
(
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    conversation_id BIGINT      NOT NULL COMMENT '会话主键',
    seq             INT         NOT NULL COMMENT '会话内事件序号，从 1 起连续递增',
    event_type      VARCHAR(32) NOT NULL COMMENT '事件类型，对应 Event.EventType',
    payload         JSON        NOT NULL COMMENT '事件载荷，可直接反序列化回 Event',
    created_at      DATETIME(3) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_event_conversation_seq (conversation_id, seq),
    KEY idx_agent_event_type (event_type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='内核事件';

-- ---------- 订单 ------------ 售后场景的操作数据，供 query_order 工具查询；不是知识库的一部分
CREATE TABLE IF NOT EXISTS order_info
(
    id           BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no     VARCHAR(64)    NOT NULL COMMENT '订单号',
    user_id      VARCHAR(64)    NOT NULL COMMENT '下单用户',
    status       VARCHAR(32)    NOT NULL COMMENT '订单状态：PAID 已付款 / SHIPPED 已发货 / DELIVERED 已签收 / CLOSED 已关闭 / REFUNDING 退款中',
    product_name VARCHAR(200)   NOT NULL COMMENT '商品名称',
    quantity     INT            NOT NULL COMMENT '购买数量',
    paid_amount  DECIMAL(12, 2) NOT NULL COMMENT '实付金额',
    created_at   DATETIME(3)    NOT NULL COMMENT '下单时间',
    updated_at   DATETIME(3)    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_info_order_no (order_no),
    KEY idx_order_info_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='订单';

-- ---------- 源文档 ----------
-- corpus 里政策文档的元数据。正文不进库，只记内容哈希，作为增量编译的输入指纹
CREATE TABLE IF NOT EXISTS source_doc
(
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    doc_id        VARCHAR(128) NOT NULL COMMENT '文档标识，等于文件名',
    title         VARCHAR(200) NOT NULL COMMENT '文档标题',
    category      VARCHAR(32)  NULL COMMENT '类目',
    version       VARCHAR(32)  NULL COMMENT '版本号',
    effective_at  DATE         NULL COMMENT '生效日期',
    status        VARCHAR(16)  NOT NULL COMMENT '状态：active 生效 / retired 已失效',
    supersedes    VARCHAR(128) NULL COMMENT '被本版本取代的 doc_id',
    superseded_by VARCHAR(128) NULL COMMENT '取代本版本的 doc_id',
    content_hash  CHAR(64)     NOT NULL COMMENT '正文 sha256，输入指纹',
    path          VARCHAR(300) NOT NULL COMMENT '相对仓库根的路径',
    created_at    DATETIME(3)  NOT NULL COMMENT '创建时间',
    updated_at    DATETIME(3)  NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_doc_doc_id (doc_id),
    KEY idx_source_doc_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='源文档';

-- ---------- Wiki 条目元数据 ----------
-- 正文以 Markdown 存在 wiki/pages/ 下，库中只有元数据；
-- 正文改动通过文件 diff 看，不写库
CREATE TABLE IF NOT EXISTS wiki_page
(
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    page_id       VARCHAR(128) NOT NULL COMMENT '条目标识，由标题规范化得到，归并时按它匹配',
    title         VARCHAR(200) NOT NULL COMMENT '条目标题',
    version       VARCHAR(32)  NULL COMMENT '版本号，取来源文档中最新的版本',
    status        VARCHAR(16)  NOT NULL COMMENT '状态：DRAFT 草稿 / ACTIVE 已生效 / RETIRED 已失效',
    effective_at  DATE         NULL COMMENT '生效日期',
    expires_at    DATE         NULL COMMENT '失效日期，为空表示长期有效',
    sources       JSON         NOT NULL COMMENT '来源 doc_id 列表',
    input_hash    CHAR(64)     NOT NULL COMMENT '输入指纹：来源文档内容哈希与版本的组合',
    superseded_by VARCHAR(128) NULL COMMENT '取代本条目的 doc_id，仅在已失效时有值',
    retired_at    DATETIME(3)  NULL COMMENT '失效时间',
    created_at    DATETIME(3)  NOT NULL COMMENT '创建时间',
    updated_at    DATETIME(3)  NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_wiki_page_page_id (page_id),
    KEY idx_wiki_page_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='Wiki 条目元数据';

-- ---------- 条目双链 ----------
CREATE TABLE IF NOT EXISTS wiki_link
(
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    from_page_id VARCHAR(128) NOT NULL COMMENT '源条目',
    to_page_id   VARCHAR(128) NULL COMMENT '目标条目，目标还不存在时为空',
    to_title     VARCHAR(200) NOT NULL COMMENT '目标条目标题',
    status       VARCHAR(16)  NOT NULL COMMENT '状态：ACTIVE 已连通 / PENDING 待创建',
    created_at   DATETIME(3)  NOT NULL COMMENT '创建时间',
    updated_at   DATETIME(3)  NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_wiki_link_from_to (from_page_id, to_title),
    KEY idx_wiki_link_to (to_page_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='条目双链';

-- ---------- 快通道条目 ----------
CREATE TABLE IF NOT EXISTS fast_entry
(
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    entry_id       VARCHAR(64)   NOT NULL COMMENT '条目标识，由问题文本规范化后哈希得到，重复派生幂等',
    scene          VARCHAR(32)   NOT NULL COMMENT '场景，如 aftersale',
    question       VARCHAR(300)  NOT NULL COMMENT '规范化后的标准问题',
    answer         TEXT          NOT NULL COMMENT '确定性答案',
    keywords       JSON          NOT NULL COMMENT '命中判定用的关键词，全部命中才算匹配',
    conditions     TEXT          NULL COMMENT '适用条件',
    confidence     DECIMAL(4, 3) NOT NULL COMMENT '置信度，低于阈值不判为命中',
    status         VARCHAR(16)   NOT NULL COMMENT '状态：CANDIDATE 候选 / ACTIVE 生效 / OFFLINE 下线',
    origin         VARCHAR(16)   NOT NULL COMMENT '来源方式：DERIVED 由 Wiki 条目派生 / DISTILLED 由慢通道流量沉淀',
    source_page_id VARCHAR(128)  NULL COMMENT '来源 Wiki 条目。派生条目必填，沉淀条目为空且只能停在候选态',
    effective_at   DATE          NULL COMMENT '生效日期',
    expires_at     DATE          NULL COMMENT '失效日期，为空表示长期有效',
    hit_count      INT           NOT NULL DEFAULT 0 COMMENT '命中次数，供流量沉淀排序',
    created_at     DATETIME(3)   NOT NULL COMMENT '创建时间',
    updated_at     DATETIME(3)   NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_fast_entry_entry_id (entry_id),
    KEY idx_fast_entry_scene_status (scene, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='快通道条目';

-- ---------- 提问日志 ----------
-- 记录每次提问走哪条通道、是否命中、耗时与 token，阶段 6 的双通道对比实验读这张表
CREATE TABLE IF NOT EXISTS query_log
(
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    ts              DATETIME(3)  NOT NULL COMMENT '提问时间',
    question        VARCHAR(500) NOT NULL COMMENT '原始问题',
    question_hash   CHAR(64)     NOT NULL COMMENT '问题文本的 sha256，用于按问题聚合',
    channel         VARCHAR(16)  NOT NULL COMMENT '通道：FASTPATH 快通道 / RAG 慢通道',
    hit             TINYINT(1)   NOT NULL COMMENT '快通道是否命中；慢通道恒为 0',
    entry_id        VARCHAR(64)  NULL COMMENT '命中的快通道条目',
    latency_ms      INT          NOT NULL COMMENT '端到端耗时，可用于 P99',
    token_in        INT          NULL COMMENT '慢通道消耗的输入 token，快通道为空',
    token_out       INT          NULL COMMENT '慢通道消耗的输出 token，快通道为空',
    conversation_id BIGINT       NULL COMMENT '关联会话',
    created_at      DATETIME(3)  NOT NULL COMMENT '写入时间',
    PRIMARY KEY (id),
    KEY idx_query_log_ts (ts),
    KEY idx_query_log_question_hash (question_hash),
    KEY idx_query_log_channel (channel, hit)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='提问日志';

-- ---------- 售后工单 ----------
CREATE TABLE IF NOT EXISTS aftersale_ticket
(
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    ticket_id       VARCHAR(64)  NOT NULL COMMENT '工单号',
    conversation_id BIGINT       NULL COMMENT '来源会话',
    session_id      VARCHAR(64)  NULL COMMENT '来源会话标识',
    order_no        VARCHAR(64)  NULL COMMENT '关联订单号',
    reason          VARCHAR(500) NOT NULL COMMENT '转人工原因',
    status          VARCHAR(16)  NOT NULL COMMENT '状态：OPEN 待处理 / CLOSED 已关闭',
    created_at      DATETIME(3)  NOT NULL COMMENT '创建时间',
    updated_at      DATETIME(3)  NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_aftersale_ticket_ticket_id (ticket_id),
    KEY idx_aftersale_ticket_conversation (conversation_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='售后工单';
