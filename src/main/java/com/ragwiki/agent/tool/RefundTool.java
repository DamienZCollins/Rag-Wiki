package com.ragwiki.agent.tool;

import com.ragwiki.agent.aftersale.AftersaleService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 按订单号发起退款。
 */
@Component
public class RefundTool implements Tool {

    private final AftersaleService aftersaleService;

    public RefundTool(AftersaleService aftersaleService) {
        this.aftersaleService = aftersaleService;
    }

    @Override
    public String name() {
        return "refund";
    }

    @Override
    public String description() {
        return "按订单号发起退款。用户明确要求退款、且订单确实符合退款条件时调用。"
                + "先用 query_order 确认订单信息，不要凭猜测调用。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "orderNo", Map.of(
                                "type", "string",
                                "description", "要退款的订单号，形如 SO20250901001"),
                        "reason", Map.of(
                                "type", "string",
                                "description", "退款原因")),
                "required", List.of("orderNo"));
    }

    @Override
    public ToolOutcome execute(Map<String, Object> arguments, ToolContext context) {
        String orderNo = String.valueOf(arguments.get("orderNo")).trim();
        Object reason = arguments.get("reason");
        AftersaleService.RefundResult result = aftersaleService.refund(
                orderNo, reason == null ? null : String.valueOf(reason));

        return ToolOutcome.ok("""
                订单号：%s
                退款金额：%s 元
                订单状态：%s
                说明：%s""".formatted(result.orderNo(), result.amount(), result.status(), result.message()));
    }
}
