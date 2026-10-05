package com.ragwiki.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** 订单，供 query_order 工具查询 */
@Getter
@Setter
@TableName("order_info")
public class OrderInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private String userId;

    /** PAID / SHIPPED / DELIVERED / CLOSED / REFUNDING */
    private String status;

    private String productName;

    private Integer quantity;

    private BigDecimal paidAmount;

    private Instant createdAt;

    private Instant updatedAt;
}
