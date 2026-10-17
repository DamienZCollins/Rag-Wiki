package com.ragwiki.rag.dto;

import java.util.List;

/**
 * 一次检索的结果。两路最高原始分一并带出，拒答判定与后续调参都要看它。
 */
public record SearchOutcome(
        String question,
        List<FusedChunk> chunks,
        boolean refused,
        double topBm25Score,
        double topVectorScore) {
}
