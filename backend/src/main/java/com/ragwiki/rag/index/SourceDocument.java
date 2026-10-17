package com.ragwiki.rag.index;

import java.nio.file.Path;
import java.util.List;

/**
 * 一篇待索引的源文档。正文用于切分，元数据随每个片段进索引，供过滤与引用。
 *
 * @param docId  原始文档标识。索引源是 Wiki 页面时，这里是该条目的主来源文档
 * @param pageId Wiki 条目标识。索引源是原始文档时为空
 */
public record SourceDocument(
        String docId,
        String pageId,
        /** 全部来源 doc_id。索引源是 Wiki 页面时是条目归并的所有原始文档 */
        List<String> sourceDocs,
        String title,
        String category,
        String version,
        String effectiveAt,
        String status,
        String body,
        Path path) {
}
