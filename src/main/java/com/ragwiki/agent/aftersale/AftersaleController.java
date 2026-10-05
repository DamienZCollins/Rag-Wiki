package com.ragwiki.agent.aftersale;

import com.ragwiki.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "售后动作", description = "建单与退款。建单只由 Agent 工具触发，退款另有这条对外接口")
@RestController
@RequestMapping("/api/aftersale")
public class AftersaleController {

    private final AftersaleService aftersaleService;

    public AftersaleController(AftersaleService aftersaleService) {
        this.aftersaleService = aftersaleService;
    }

    @Operation(summary = "发起退款", description = "只改订单状态，真实的支付网关不在本项目范围内")
    @PostMapping("/refund")
    public Result<AftersaleService.RefundResult> refund(@Valid @RequestBody RefundRequest request) {
        return Result.ok(aftersaleService.refund(request.orderNo(), request.reason()));
    }

    public record RefundRequest(
            @NotBlank(message = "不能为空") String orderNo,
            String reason) {
    }
}
