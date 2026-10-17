package com.ragwiki.rag.search;

import com.ragwiki.rag.RagProperties;
import com.ragwiki.rag.dto.ChunkHit;
import com.ragwiki.rag.dto.ChunkMeta;
import com.ragwiki.rag.dto.FusedChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF 融合：score = Σ 1 / (k + rank)。
 *
 * <p>按名次融合而不是按原始分：BM25 的分与余弦相似度不在一个量纲上，直接加权需要归一化，
 * 而归一化本身又要引入新的假设。RRF 只用「排第几」，省掉这一步。
 * 只在一路出现的片段按单路得分参与排序，不做惩罚。
 */
@Component
public class RrfFuser {

    private final RagProperties properties;

    public RrfFuser(RagProperties properties) {
        this.properties = properties;
    }

    public List<FusedChunk> fuse(List<ChunkHit> bm25Hits, List<ChunkHit> vectorHits) {
        Map<String, Accumulator> byChunkId = new LinkedHashMap<>();
        for (ChunkHit hit : bm25Hits) {
            byChunkId.computeIfAbsent(hit.meta().chunkId(), id -> new Accumulator(hit.meta())).bm25 = hit;
        }
        for (ChunkHit hit : vectorHits) {
            byChunkId.computeIfAbsent(hit.meta().chunkId(), id -> new Accumulator(hit.meta())).vector = hit;
        }

        int k = properties.rrf().k();
        List<FusedChunk> fused = new ArrayList<>(byChunkId.size());
        for (Accumulator accumulator : byChunkId.values()) {
            double score = 0;
            if (accumulator.bm25 != null) {
                score += 1.0 / (k + accumulator.bm25.rank());
            }
            if (accumulator.vector != null) {
                score += 1.0 / (k + accumulator.vector.rank());
            }
            fused.add(new FusedChunk(
                    accumulator.meta,
                    accumulator.bm25 == null ? null : accumulator.bm25.score(),
                    accumulator.bm25 == null ? null : accumulator.bm25.rank(),
                    accumulator.vector == null ? null : accumulator.vector.score(),
                    accumulator.vector == null ? null : accumulator.vector.rank(),
                    score));
        }

        // 分数相同再按 BM25 原始分兜底，保证同样输入每次排序一致
        fused.sort(Comparator
                .comparingDouble(FusedChunk::rrfScore).reversed()
                .thenComparing(chunk -> chunk.bm25Score() == null ? 0.0 : chunk.bm25Score(),
                        Comparator.reverseOrder()));
        return fused;
    }

    private static final class Accumulator {

        private final ChunkMeta meta;
        private ChunkHit bm25;
        private ChunkHit vector;

        private Accumulator(ChunkMeta meta) {
            this.meta = meta;
        }
    }
}
