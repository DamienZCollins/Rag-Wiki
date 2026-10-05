package com.ragwiki.agent.tool;

import com.ragwiki.agent.aftersale.AftersaleService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 生成售后工单并转人工。用户的问题超出政策范围、或者需要人工判断时调用。
 */
@Component
public class CreateTicketTool implements Tool {

    private final AftersaleService aftersaleService;

    public CreateTicketTool(AftersaleService aftersaleService) {
        this.aftersaleService = aftersaleService;
    }

    @Override
    public String name() {
        return "create_ticket";
    }

    @Override
    public String description() {
        return "生成售后工单并转人工处理，用于你无法自助解决、需要人工介入的情况。"
                + "调用后会话状态会变为已转人工。用户明确要求人工、或知识库里确实没有依据时才调用。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "reason", Map.of(
                                "type", "string",
                                "description", "转人工的原因，写清楚用户要解决的问题"),
                        "orderNo", Map.of(
                                "type", "string",
                                "description", "关联的订单号，用户没提到就留空")),
                "required", List.of("reason"));
    }

    @Override
    public ToolOutcome execute(Map<String, Object> arguments, ToolContext context) {
        String reason = String.valueOf(arguments.get("reason")).trim();
        Object orderNo = arguments.get("orderNo");
        AftersaleService.TicketResult result = aftersaleService.createTicket(
                context == null ? null : context.sessionId(),
                reason,
                orderNo == null ? null : String.valueOf(orderNo));

        return ToolOutcome.ok("""
                已生成售后工单，会话已转为人工处理：
                工单号：%s
                状态：%s
                事由：%s""".formatted(result.ticketId(), result.status(), result.reason()));
    }
}
