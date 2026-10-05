package com.ragwiki.rag.index;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 写进 ES 的片段文档。字段名与 es/knowledge-index.json 的 mapping 一一对应。
 * embedding 只在写入时用，查询侧不会反序列化它。
 */
public record KnowledgeChunk(
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("doc_id") String docId,
        @JsonProperty("page_id") String pageId,
        @JsonProperty("source_docs") List<String> sourceDocs,
        @JsonProperty("title") String title,
        @JsonProperty("heading_path") String headingPath,
        @JsonProperty("content") String content,
        @JsonProperty("category") String category,
        @JsonProperty("version") String version,
        @JsonProperty("status") String status,
        @JsonProperty("effective_at") String effectiveAt,
        @JsonProperty("chunk_index") int chunkIndex,
        @JsonProperty("embedding") float[] embedding) {
}
