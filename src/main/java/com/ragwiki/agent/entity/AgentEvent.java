package com.ragwiki.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 内核事件，append-only */
@Getter
@Setter
@TableName("agent_event")
public class AgentEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    /** 会话内事件序号，从 1 起连续递增 */
    private Integer seq;

    /** 对应 Event.EventType */
    private String eventType;

    /** 事件载荷，JSON 字符串，可直接反序列化回 Event */
    private String payload;

    private Instant createdAt;
}
