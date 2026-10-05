package com.ragwiki.rag.index;

import java.nio.file.Path;

/**
 * 一篇源文档。正文与元数据分开，正文用于切分，元数据随每个片段一起进索引，供过滤与引用。
 */
public record SourceDocument(
        String docId,
        String title,
        String category,
        String version,
        String effectiveAt,
        String status,
        String body,
        Path path) {
}
