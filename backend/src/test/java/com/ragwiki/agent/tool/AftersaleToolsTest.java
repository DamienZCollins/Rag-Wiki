package com.ragwiki.agent.tool;

import com.ragwiki.agent.aftersale.AftersaleService;
import com.ragwiki.agent.entity.OrderInfo;
import com.ragwiki.agent.mapper.OrderInfoMapper;
import com.ragwiki.common.time.Times;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 三个售后工具的对外行为：渲染给模型看的文本、参数透传、订单状态的中文标注。
 */
class AftersaleToolsTest {

    private final OrderInfoMapper orderInfoMapper = mock(OrderInfoMapper.class);
    private final AftersaleService aftersaleService = mock(AftersaleService.class);

    private static OrderInfo order(String orderNo, String status) {
        OrderInfo order = new OrderInfo();
        order.setOrderNo(orderNo);
        order.setStatus(status);
        order.setProductName("阳澄湖大闸蟹");
        order.setQuantity(2);
        order.setPaidAmount(new BigDecimal("268.00"));
        order.setCreatedAt(Instant.parse("2025-09-01T02:00:00Z"));
        return order;
    }

    @Test
    void queryOrderRendersStatusInChineseWithTraceableFields() {
        when(orderInfoMapper.selectOne(any())).thenReturn(order("SO20250901001", "DELIVERED"));

        ToolOutcome outcome = new QueryOrderTool(orderInfoMapper)
                .execute(Map.of("orderNo", " SO20250901001 "), ToolContext.empty());

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.content())
                .contains("SO20250901001")
                .contains("已签收")
                .contains("阳澄湖大闸蟹 × 2")
                .contains("268.00")
                // 时间统一按 Asia/Shanghai 展示
                .contains(Times.format(Instant.parse("2025-09-01T02:00:00Z")));
    }

    @Test
    void queryOrderReturnsActionableErrorWhenMissing() {
        when(orderInfoMapper.selectOne(any())).thenReturn(null);

        ToolOutcome outcome = new QueryOrderTool(orderInfoMapper)
                .execute(Map.of("orderNo", "SO00000000"), ToolContext.empty());

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.content()).contains("没有查到").contains("核对订单号");
        // 工具失败是正常业务结果，不是异常
    }

    @Test
    void refundToolPassesOrderAndReasonThrough() {
        when(aftersaleService.refund("SO20250901001", "商品破损"))
                .thenReturn(new AftersaleService.RefundResult(
                        "SO20250901001", "268.00", "REFUNDING", "退款已发起"));

        ToolOutcome outcome = new RefundTool(aftersaleService).execute(
                Map.of("orderNo", " SO20250901001 ", "reason", "商品破损"), ToolContext.empty());

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.content()).contains("268.00").contains("REFUNDING");
        verify(aftersaleService).refund("SO20250901001", "商品破损");
    }

    @Test
    void createTicketToolInjectsSessionFromContext() {
        when(aftersaleService.createTicket("s-1", "要人工", null))
                .thenReturn(new AftersaleService.TicketResult("T20260102030405-1234", "要人工", "OPEN"));

        ToolOutcome outcome = new CreateTicketTool(aftersaleService).execute(
                Map.of("reason", "要人工"), new ToolContext("s-1", 42L));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.content()).contains("T20260102030405-1234").contains("OPEN");
        verify(aftersaleService).createTicket("s-1", "要人工", null);
    }

    @Test
    void toolsExposeSchemasTheModelCanFollow() {
        assertThat(new RefundTool(aftersaleService).parameterSchema())
                .containsEntry("required", java.util.List.of("orderNo"));
        assertThat(new CreateTicketTool(aftersaleService).parameterSchema())
                .containsEntry("required", java.util.List.of("reason"));
        assertThat(new QueryOrderTool(orderInfoMapper).name()).isEqualTo("query_order");
        assertThat(new RefundTool(aftersaleService).name()).isEqualTo("refund");
        assertThat(new CreateTicketTool(aftersaleService).name()).isEqualTo("create_ticket");
    }
}
