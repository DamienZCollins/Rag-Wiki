package com.ragwiki.rag.dto;

/**
 * RRF 融合后的片段。
 * 两路的原始分与名次都留着：原始分用于拒答判定，名次用于解释排序为什么是这样。
 */
public record FusedChunk(
        ChunkMeta meta,
        Double bm25Score,
        Integer bm25Rank,
        Double vectorScore,
        Integer vectorRank,
        double rrfScore) {
}
