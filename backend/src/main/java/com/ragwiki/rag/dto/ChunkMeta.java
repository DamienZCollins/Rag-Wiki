package com.ragwiki.rag.dto;

import com.ragwiki.rag.index.KnowledgeChunk;

import java.util.List;

/**
 * 片段元数据。引用溯源要的全部信息都在这里，两个召回路径与融合层共用。
 */
public record ChunkMeta(
        String chunkId,
        String docId,
        /** Wiki 条目标识，索引源是原始文档时为空 */
        String pageId,
        /** 全部来源 doc_id，用于追溯到原始文档 */
        List<String> sourceDocs,
        String title,
        String headingPath,
        String content,
        String category,
        String version) {

    public static ChunkMeta from(KnowledgeChunk chunk) {
        return new ChunkMeta(
                chunk.chunkId(),
                chunk.docId(),
                chunk.pageId(),
                chunk.sourceDocs() == null ? List.of() : chunk.sourceDocs(),
                chunk.title(),
                chunk.headingPath(),
                chunk.content(),
                chunk.category(),
                chunk.version());
    }
}
