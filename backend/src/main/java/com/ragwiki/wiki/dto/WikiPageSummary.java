package com.ragwiki.wiki.dto;

import com.ragwiki.wiki.schema.PageContent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 条目视图。列表用摘要，详情多带正文与双向链接。
 */
public record WikiPageSummary(
        @Schema(description = "条目标识") String pageId,
        String title,
        String version,
        @Schema(description = "DRAFT / ACTIVE / RETIRED") String status,
        LocalDate effectiveAt,
        LocalDate expiresAt,
        @Schema(description = "来源 doc_id 列表，可追溯到原始文档") List<String> sources,
        Instant updatedAt,
        @Schema(description = "指向其它条目的链接数") int outboundCount,
        @Schema(description = "被其它条目指向的数量") int inboundCount) {

    @Schema(description = "条目详情")
    public record WikiPageDetail(
            WikiPageSummary summary,
            @Schema(description = "正文结构") PageContent content,
            @Schema(description = "出链") List<Link> links,
            @Schema(description = "反向链接") List<Link> backlinks) {
    }

    @Schema(description = "一条双链")
    public record Link(
            @Schema(description = "对端条目标识，目标还不存在时为空") String pageId,
            String title,
            @Schema(description = "ACTIVE 已连通 / PENDING 待创建") String status) {
    }
}
