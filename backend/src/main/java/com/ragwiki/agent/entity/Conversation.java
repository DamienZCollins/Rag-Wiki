package com.ragwiki.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 会话 */
@Getter
@Setter
@TableName("conversation")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private String userId;

    private String title;

    /** ACTIVE 进行中 / RESOLVED 已解决 / TRANSFERRED 已转人工 */
    private String status;

    private Instant createdAt;

    private Instant updatedAt;
}
