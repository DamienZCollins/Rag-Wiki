package com.ragwiki.rag.dto;

/**
 * 单路召回的一条命中。score 是该路的原始分，rank 从 1 开始，是 RRF 融合的依据。
 */
public record ChunkHit(ChunkMeta meta, double score, int rank) {
}
