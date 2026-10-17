package com.ragwiki.wiki.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 编译入参。
 * 不给 sourceDocId 就编译全部；incremental 为 true 时只重算输入变化的文档。
 */
@Schema(description = "编译入参")
public record CompileRequest(
        @Schema(description = "只编译这一篇源文档，留空表示全部", example = "policy-freight-rule-v2024")
        String sourceDocId,

        @Schema(description = "是否增量编译：只重算输入变化的文档", example = "true")
        Boolean incremental) {

    public boolean incrementalOrDefault() {
        return incremental == null || incremental;
    }
}
