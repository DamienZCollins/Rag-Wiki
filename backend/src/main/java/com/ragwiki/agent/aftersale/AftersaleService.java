package com.ragwiki.agent.aftersale;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ragwiki.agent.entity.AftersaleTicket;
import com.ragwiki.agent.entity.Conversation;
import com.ragwiki.agent.entity.OrderInfo;
import com.ragwiki.agent.mapper.AftersaleTicketMapper;
import com.ragwiki.agent.mapper.ConversationMapper;
import com.ragwiki.agent.mapper.OrderInfoMapper;
import com.ragwiki.agent.store.ConversationStore;
import com.ragwiki.common.exception.BusinessException;
import com.ragwiki.common.result.ResultCode;
import com.ragwiki.common.time.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 售后动作：建单转人工、发起退款。
 * 都由 Agent 工具调用触发，不由用户直连——用户直连的只有退款那条对外接口。
 */
@Service
public class AftersaleService {

    private static final Logger log = LoggerFactory.getLogger(AftersaleService.class);
    private static final DateTimeFormatter TICKET_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String ORDER_STATUS_REFUNDING = "REFUNDING";
    private static final String ORDER_STATUS_CLOSED = "CLOSED";

    private final ConversationStore conversationStore;
    private final ConversationMapper conversationMapper;
    private final OrderInfoMapper orderInfoMapper;
    private final AftersaleTicketMapper ticketMapper;

    public AftersaleService(ConversationStore conversationStore,
                            ConversationMapper conversationMapper,
                            OrderInfoMapper orderInfoMapper,
                            AftersaleTicketMapper ticketMapper) {
        this.conversationStore = conversationStore;
        this.conversationMapper = conversationMapper;
        this.orderInfoMapper = orderInfoMapper;
        this.ticketMapper = ticketMapper;
    }

    public record TicketResult(String ticketId, String reason, String status) {
    }

    public record RefundResult(String orderNo, String amount, String status, String message) {
    }

    /**
     * 建单并转人工。会话状态同步置为「已转人工」，这是工单与会话的联动，不另开接口。
     */
    public TicketResult createTicket(String sessionId, String reason, String orderNo) {
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "转人工原因不能为空");
        }

        Long conversationId = null;
        if (StringUtils.hasText(sessionId)) {
            try {
                conversationId = conversationStore.getOrCreate(sessionId, reason).getId();
            } catch (Exception e) {
                log.warn("建单时读取会话失败，工单仍照常创建 sessionId={}", sessionId, e);
            }
        }

        Instant now = Instant.now();
        AftersaleTicket ticket = new AftersaleTicket();
        ticket.setTicketId(nextTicketId());
        ticket.setConversationId(conversationId);
        ticket.setSessionId(sessionId);
        ticket.setOrderNo(StringUtils.hasText(orderNo) ? orderNo.strip() : null);
        ticket.setReason(truncate(reason, 500));
        ticket.setStatus(AftersaleTicket.STATUS_OPEN);
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        ticketMapper.insert(ticket);

        if (conversationId != null) {
            Conversation update = new Conversation();
            update.setId(conversationId);
            update.setStatus("TRANSFERRED");
            update.setUpdatedAt(now);
            conversationMapper.updateById(update);
        }

        log.info("已生成售后工单 {}，会话 {} 转为已转人工", ticket.getTicketId(), conversationId);
        return new TicketResult(ticket.getTicketId(), ticket.getReason(), ticket.getStatus());
    }

    /** 按订单号发起退款。这里改的是订单状态，真实的支付网关不在本项目范围内 */
    public RefundResult refund(String orderNo, String reason) {
        if (!StringUtils.hasText(orderNo)) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "订单号不能为空");
        }
        String normalized = orderNo.strip();

        OrderInfo order = orderInfoMapper.selectOne(
                Wrappers.<OrderInfo>lambdaQuery().eq(OrderInfo::getOrderNo, normalized));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "没有查到订单号 " + normalized + " 对应的订单");
        }
        if (ORDER_STATUS_CLOSED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "订单已关闭，无法发起退款");
        }
        if (ORDER_STATUS_REFUNDING.equals(order.getStatus())) {
            return new RefundResult(normalized, order.getPaidAmount().toPlainString(),
                    order.getStatus(), "该订单已在退款流程中，请勿重复发起");
        }

        OrderInfo update = new OrderInfo();
        update.setId(order.getId());
        update.setStatus(ORDER_STATUS_REFUNDING);
        update.setUpdatedAt(Instant.now());
        orderInfoMapper.updateById(update);

        log.info("订单 {} 已发起退款，金额 {}，原因 {}", normalized, order.getPaidAmount().toPlainString(), reason);
        return new RefundResult(normalized, order.getPaidAmount().toPlainString(),
                ORDER_STATUS_REFUNDING,
                "退款已发起，金额按订单实付金额退回，到账时效按支付方式确定");
    }

    public AftersaleTicket findByTicketId(String ticketId) {
        return ticketMapper.selectOne(
                Wrappers.<AftersaleTicket>lambdaQuery().eq(AftersaleTicket::getTicketId, ticketId));
    }

    private static String nextTicketId() {
        String stamp = TICKET_TIME.format(ZonedDateTime.now(Times.ZONE));
        return "T" + stamp + "-" + (1000 + ThreadLocalRandom.current().nextInt(9000));
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
