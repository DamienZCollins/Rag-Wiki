package com.ragwiki.common.markdown;

import java.nio.file.Path;
import java.util.List;

/**
 * 一篇带 frontmatter 的 Markdown 源文档。
 * 政策语料与 Wiki 条目共用这个形状，只是各自的字段取用不同。
 */
public record MarkdownSource(
        String id,
        String title,
        String category,
        String version,
        String effectiveAt,
        String status,
        String supersedes,
        String supersededBy,
        List<String> sources,
        String contentHash,
        String body,
        Path path) {

    public boolean isActive() {
        return "active".equalsIgnoreCase(status);
    }
}
