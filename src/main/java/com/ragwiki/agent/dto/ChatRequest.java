package com.ragwiki.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 一轮对话的入参。
 */
@Schema(description = "对话入参")
public record ChatRequest(

        @Schema(description = "会话标识，同一个标识下的多轮对话共享上下文", example = "s-20251005-0001")
        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "长度不能超过 64")
        String sessionId,

        @Schema(description = "用户输入", example = "我订单 SO20250901001 现在到哪了？")
        @NotBlank(message = "不能为空")
        @Size(max = 2000, message = "长度不能超过 2000")
        String message) {
}
