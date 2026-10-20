package com.ragwiki.agent.aftersale;

import com.ragwiki.agent.entity.AftersaleTicket;
import com.ragwiki.agent.entity.OrderInfo;
import com.ragwiki.agent.mapper.AftersaleTicketMapper;
import com.ragwiki.agent.mapper.ConversationMapper;
import com.ragwiki.agent.mapper.OrderInfoMapper;
import com.ragwiki.agent.store.ConversationStore;
import com.ragwiki.common.exception.BusinessException;
import com.ragwiki.common.result.ResultCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 售后动作的两个业务规则：建单联动会话状态、退款只允许从非关闭非退款中状态发起。
 */
class AftersaleServiceTest {

    private final ConversationStore conversationStore = mock(ConversationStore.class);
    private final ConversationMapper conversationMapper = mock(ConversationMapper.class);
    private final OrderInfoMapper orderInfoMapper = mock(OrderInfoMapper.class);
    private final AftersaleTicketMapper ticketMapper = mock(AftersaleTicketMapper.class);

    private final AftersaleService service = new AftersaleService(
            conversationStore, conversationMapper, orderInfoMapper, ticketMapper);

    private static OrderInfo order(String orderNo, String status) {
        OrderInfo order = new OrderInfo();
        order.setId(11L);
        order.setOrderNo(orderNo);
        order.setStatus(status);
        order.setProductName("阳澄湖大闸蟹");
        order.setQuantity(2);
        order.setPaidAmount(new BigDecimal("268.00"));
        order.setCreatedAt(Instant.parse("2025-09-01T02:00:00Z"));
        return order;
    }

    // ---------- 退款 ----------

    private AftersaleService.RefundResult refund(OrderInfo order) {
        when(orderInfoMapper.selectOne(any())).thenReturn(order);
        return service.refund(order.getOrderNo(), "商品破损");
    }

    @Test
    void refundMovesOrderIntoRefunding() {
        AftersaleService.RefundResult result = refund(order("SO20250901001", "DELIVERED"));

        assertThat(result.status()).isEqualTo("REFUNDING");
        assertThat(result.amount()).isEqualTo("268.00");
        assertThat(result.message()).contains("退款已发起");
        verify(orderInfoMapper).updateById(any(OrderInfo.class));
    }

    @Test
    void refundRejectsClosedOrder() {
        OrderInfo closed = order("SO20250928006", "CLOSED");

        assertThatThrownBy(() -> refund(closed))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getResultCode()).isEqualTo(ResultCode.BUSINESS_ERROR);
                    assertThat(e.getMessage()).contains("已关闭");
                });
        // 状态没变，不该写库
        verify(orderInfoMapper, never()).updateById(org.mockito.ArgumentMatchers.<OrderInfo>any());
    }

    @Test
    void refundOnAlreadyRefundingOrderIsIdempotent() {
        AftersaleService.RefundResult result = refund(order("SO20250903002", "REFUNDING"));

        assertThat(result.status()).isEqualTo("REFUNDING");
        assertThat(result.message()).contains("请勿重复发起");
        verify(orderInfoMapper, never()).updateById(org.mockito.ArgumentMatchers.<OrderInfo>any());
    }

    @Test
    void refundRejectsUnknownOrder() {
        when(orderInfoMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.refund("SO00000000", "退款"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(ResultCode.NOT_FOUND));
    }

    @Test
    void refundRejectsBlankOrderNo() {
        assertThatThrownBy(() -> service.refund("  ", "退款"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(ResultCode.PARAM_INVALID));
        verify(orderInfoMapper, never()).selectOne(any());
    }

    // ---------- 建单 ----------

    @Test
    void createTicketLinksConversationAndMarksItTransferred() {
        com.ragwiki.agent.entity.Conversation conversation = new com.ragwiki.agent.entity.Conversation();
        conversation.setId(42L);
        conversation.setSessionId("s-1");
        when(conversationStore.getOrCreate("s-1", "要人工")).thenReturn(conversation);

        AftersaleService.TicketResult result = service.createTicket("s-1", "要人工", "SO20250901001");

        assertThat(result.ticketId()).startsWith("T").contains("-");
        assertThat(result.status()).isEqualTo(AftersaleTicket.STATUS_OPEN);
        verify(ticketMapper).insert(any(AftersaleTicket.class));
        verify(conversationMapper).updateById(any(com.ragwiki.agent.entity.Conversation.class));
    }

    @Test
    void createTicketStillSucceedsWhenConversationLookupFails() {
        // 会话读不到不该挡住建单，工单本身就是留给人工看的东西
        when(conversationStore.getOrCreate(any(), any())).thenThrow(new IllegalStateException("db down"));

        AftersaleService.TicketResult result = service.createTicket("s-2", "要人工", null);

        assertThat(result.ticketId()).isNotBlank();
        verify(ticketMapper).insert(any(AftersaleTicket.class));
        verify(conversationMapper, never()).updateById(org.mockito.ArgumentMatchers.<com.ragwiki.agent.entity.Conversation>any());
    }

    @Test
    void createTicketRejectsBlankReason() {
        assertThatThrownBy(() -> service.createTicket("s-1", "  ", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(ResultCode.PARAM_INVALID));
        verify(ticketMapper, never()).insert(any(AftersaleTicket.class));
    }

    @Test
    void createTicketTruncatesLongReason() {
        String longReason = "很长的原因".repeat(200);

        service.createTicket(null, longReason, null);

        org.mockito.ArgumentCaptor<AftersaleTicket> captor =
                org.mockito.ArgumentCaptor.forClass(AftersaleTicket.class);
        verify(ticketMapper).insert(captor.capture());
        assertThat(captor.getValue().getReason()).hasSize(500);
    }
}
