package com.ragwiki.wiki.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 编译报告。新建 / 更新 / 失效 / 跳过 的条目与文档都列出来，便于核对数字与实际改动是否一致。
 */
@Schema(description = "编译报告")
public record CompileReport(
        @Schema(description = "本次参与编译的文档") List<String> compiledDocs,
        @Schema(description = "因输入未变化而跳过的文档") List<String> skippedDocs,
        @Schema(description = "增量规划说明") List<String> notes,
        @Schema(description = "新建条目数") int created,
        @Schema(description = "更新条目数") int updated,
        @Schema(description = "失效条目数") int retired,
        @Schema(description = "编译失败的文档及原因，单篇失败不影响整批") List<String> failedDocs,
        List<String> createdPages,
        List<String> updatedPages,
        List<String> retiredPages,
        @Schema(description = "耗时毫秒") long elapsedMillis,
        @Schema(description = "编译期模型调用次数") int modelCalls,
        @Schema(description = "编译期输入 token") long promptTokens,
        @Schema(description = "编译期输出 token") long completionTokens) {

    public long totalTokens() {
        return promptTokens + completionTokens;
    }
}
