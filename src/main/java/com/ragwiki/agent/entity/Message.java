package com.ragwiki.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 会话消息，面向展示的投影 */
@Getter
@Setter
@TableName("message")
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    /** 会话内序号，从 1 起连续递增 */
    private Integer seq;

    /** USER / ASSISTANT / TOOL */
    private String role;

    private String content;

    /** 工具调用载荷，JSON 字符串，仅 ASSISTANT 消息有值 */
    private String toolCalls;

    /** 工具结果对应的调用标识，仅 TOOL 消息有值 */
    private String toolCallId;

    private Instant createdAt;
}
