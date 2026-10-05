package com.ragwiki.agent.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ragwiki.agent.entity.OrderInfo;
import com.ragwiki.agent.mapper.OrderInfoMapper;
import com.ragwiki.common.time.Times;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 按订单号查询订单状态、商品与金额。
 */
@Component
public class QueryOrderTool implements Tool {

    private static final Map<String, String> STATUS_LABELS = Map.of(
            "PAID", "已付款待发货",
            "SHIPPED", "已发货",
            "DELIVERED", "已签收",
            "CLOSED", "已关闭",
            "REFUNDING", "退款处理中");

    private final OrderInfoMapper orderInfoMapper;

    public QueryOrderTool(OrderInfoMapper orderInfoMapper) {
        this.orderInfoMapper = orderInfoMapper;
    }

    @Override
    public String name() {
        return "query_order";
    }

    @Override
    public String description() {
        return "按订单号查询订单的当前状态、商品与实付金额。用户问到某个具体订单时调用，不要凭记忆回答订单信息。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "orderNo", Map.of(
                                "type", "string",
                                "description", "订单号，形如 SO20250901001")),
                "required", List.of("orderNo"));
    }

    @Override
    public ToolOutcome execute(Map<String, Object> arguments) {
        String orderNo = String.valueOf(arguments.get("orderNo")).trim();
        OrderInfo order = orderInfoMapper.selectOne(
                Wrappers.<OrderInfo>lambdaQuery().eq(OrderInfo::getOrderNo, orderNo));

        if (order == null) {
            return ToolOutcome.error("没有查到订单号 " + orderNo + " 对应的订单，请核对订单号是否正确。");
        }
        return ToolOutcome.ok(format(order));
    }

    private String format(OrderInfo order) {
        return """
                订单号：%s
                状态：%s
                商品：%s × %d
                实付金额：%s 元
                下单时间：%s""".formatted(
                order.getOrderNo(),
                STATUS_LABELS.getOrDefault(order.getStatus(), order.getStatus()),
                order.getProductName(),
                order.getQuantity(),
                order.getPaidAmount().toPlainString(),
                Times.format(order.getCreatedAt()));
    }
}
