package com.ragwiki.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "检索入参")
public record RagSearchRequest(

        @Schema(description = "用户问题，口语化问法也能召回", example = "退货的邮费谁掏")
        @NotBlank(message = "不能为空")
        @Size(max = 500, message = "长度不能超过 500")
        String question,

        @Schema(description = "指定物理索引，用于索引源对照实验；留空走别名", example = "knowledge_doc_v1")
        String index) {

    public RagSearchRequest(String question) {
        this(question, null);
    }
}
