package com.ragwiki.wiki.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * lint 结果。按严重程度分级，每条都能定位到具体条目。
 */
@Schema(description = "lint 结果")
public record LintReport(
        @Schema(description = "条目总数") int pageCount,
        @Schema(description = "矛盾：同一概念在不同条目里给出冲突结论") List<LintIssue> conflicts,
        @Schema(description = "过期：生效期已到但状态或有效期有问题") List<LintIssue> expired,
        @Schema(description = "孤页：双向都没有链接") List<LintIssue> orphans,
        @Schema(description = "待创建：双链指向的条目还不存在") List<LintIssue> pendingLinks) {

    public int totalIssues() {
        return conflicts.size() + expired.size() + orphans.size() + pendingLinks.size();
    }

    @Schema(description = "一条 lint 问题")
    public record LintIssue(
            @Schema(description = "问题类型") Kind kind,
            @Schema(description = "严重程度") Severity severity,
            @Schema(description = "条目标识") String pageId,
            @Schema(description = "条目标题") String title,
            @Schema(description = "问题说明") String detail,
            @Schema(description = "相关条目，矛盾检测时指向另一方") String relatedPageId,
            @Schema(description = "相关条目标题") String relatedTitle) {

        public static LintIssue of(Kind kind, Severity severity, String pageId, String title, String detail) {
            return new LintIssue(kind, severity, pageId, title, detail, null, null);
        }
    }

    public enum Kind {
        CONFLICT,
        EXPIRED,
        ORPHAN,
        PENDING_LINK
    }

    public enum Severity {
        HIGH,
        MEDIUM,
        LOW
    }
}
