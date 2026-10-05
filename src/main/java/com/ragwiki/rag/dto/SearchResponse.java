package com.ragwiki.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 检索接口的响应。两路原始分与名次都带出来，方便看融合到底做了什么。
 */
@Schema(description = "检索结果")
public record SearchResponse(
        @Schema(description = "原始问题") String question,
        @Schema(description = "是否拒答：两路最高原始分都低于各自阈值时为 true，chunks 为空")
        boolean refused,
        @Schema(description = "BM25 单路最高原始分", example = "18.42") double topBm25Score,
        @Schema(description = "向量单路最高相似度", example = "0.78") double topVectorScore,
        @Schema(description = "按 RRF 融合排序的片段") List<Chunk> chunks) {

    @Schema(description = "召回片段，自带引用来源")
    public record Chunk(
            String chunkId,
            String docId,
            String title,
            @Schema(description = "标题路径，如「运费收取与承担规则 > 运费补贴上限」") String headingPath,
            String content,
            String category,
            String version,
            @Schema(description = "BM25 原始分，未命中该路为 null") Double bm25Score,
            @Schema(description = "BM25 名次，未命中该路为 null") Integer bm25Rank,
            @Schema(description = "向量相似度，未命中该路为 null") Double vectorScore,
            @Schema(description = "向量名次，未命中该路为 null") Integer vectorRank,
            @Schema(description = "RRF 融合分") double rrfScore) {
    }
}
