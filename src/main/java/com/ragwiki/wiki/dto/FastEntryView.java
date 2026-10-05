package com.ragwiki.wiki.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 快通道条目视图。候选选项列表用它，命中结果也用它。
 */
@Schema(description = "快通道条目")
public record FastEntryView(
        String entryId,
        String scene,
        @Schema(description = "标准问题") String question,
        @Schema(description = "确定性答案") String answer,
        @Schema(description = "命中判定用的关键词") List<String> keywords,
        @Schema(description = "置信度") double confidence,
        @Schema(description = "CANDIDATE / ACTIVE / OFFLINE") String status,
        @Schema(description = "DERIVED 由 Wiki 条目派生 / DISTILLED 由慢通道流量沉淀") String origin,
        @Schema(description = "来源 Wiki 条目，沉淀条目为空") String sourcePageId,
        @Schema(description = "来源条目标题") String sourceTitle,
        Integer hitCount) {
}
