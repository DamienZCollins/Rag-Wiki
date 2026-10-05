package com.ragwiki.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "检索入参")
public record RagSearchRequest(

        @Schema(description = "用户问题，口语化问法也能召回", example = "退货的邮费谁掏")
        @NotBlank(message = "不能为空")
        @Size(max = 500, message = "长度不能超过 500")
        String question) {
}
