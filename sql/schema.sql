-- RAG-Wiki 建表脚本
-- 可在空库上重复执行：全部使用 CREATE TABLE IF NOT EXISTS
-- 时间字段统一 datetime(3)，写入 Asia/Shanghai 墙上时间（连接参数已带 serverTimezone=Asia/Shanghai）
--
-- 执行方式：
--   docker exec -i -e MYSQL_PWD=rag_wiki rag-wiki-mysql mysql -u rag_wiki rag_wiki < sql/schema.sql
--
-- 注意：IF NOT EXISTS 只保证重复执行不报错，不做结构变更。
-- 已存在的表需要改结构时，本地直接 DROP 后重跑，或手写 ALTER。

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
