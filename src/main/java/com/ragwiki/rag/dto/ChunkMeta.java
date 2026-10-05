package com.ragwiki.rag.dto;

import com.ragwiki.rag.index.KnowledgeChunk;

/**
 * 片段元数据。引用溯源要的全部信息都在这里，两个召回路径与融合层共用。
 */
public record ChunkMeta(
        String chunkId,
        String docId,
        String title,
        String headingPath,
        String content,
        String category,
        String version) {

    public static ChunkMeta from(KnowledgeChunk chunk) {
        return new ChunkMeta(
                chunk.chunkId(),
                chunk.docId(),
                chunk.title(),
                chunk.headingPath(),
                chunk.content(),
                chunk.category(),
                chunk.version());
    }
}
