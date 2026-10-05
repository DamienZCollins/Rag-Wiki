package com.ragwiki.wiki.schema;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 提问日志。每次提问一条，记走哪条通道、是否命中、耗时与 token。
 */
@Getter
@Setter
@TableName("query_log")
public class QueryLog {

    public static final String CHANNEL_FASTPATH = "FASTPATH";
    public static final String CHANNEL_RAG = "RAG";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Instant ts;

    private String question;

    private String questionHash;

    private String channel;

    private Boolean hit;

    private String entryId;

    private Integer latencyMs;

    private Integer tokenIn;

    private Integer tokenOut;

    private Long conversationId;

    private Instant createdAt;
}
