package com.ragwiki.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 售后工单。Agent 无法自助解决时生成 */
@Getter
@Setter
@TableName("aftersale_ticket")
public class AftersaleTicket {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ticketId;

    private Long conversationId;

    private String sessionId;

    private String orderNo;

    private String reason;

    private String status;

    private Instant createdAt;

    private Instant updatedAt;
}
