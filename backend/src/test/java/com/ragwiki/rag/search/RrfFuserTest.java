package com.ragwiki.rag.search;

import com.ragwiki.rag.RagProperties;
import com.ragwiki.rag.dto.ChunkHit;
import com.ragwiki.rag.dto.ChunkMeta;
import com.ragwiki.rag.dto.FusedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RRF 融合：按名次而不是原始分融合，两路分数量纲不同，直接加权要额外引入归一化假设。
 */
class RrfFuserTest {

    private static final int K = 60;

    private final RrfFuser fuser = new RrfFuser(new RagProperties(
            null, null, new RagProperties.Rrf(K), 50, 8, null));

    private static ChunkMeta meta(String chunkId) {
        return new ChunkMeta(chunkId, chunkId.split("#")[0], null, List.of(),
                "标题 " + chunkId, "标题 > 小节", "内容 " + chunkId, "运费", "v2025.1");
    }

    private static ChunkHit hit(String chunkId, double score, int rank) {
        return new ChunkHit(meta(chunkId), score, rank);
    }

    private static double expectedScore(int rank) {
        return 1.0 / (K + rank);
    }

    @Test
    void chunkFoundByBothPathsScoresHigherThanEitherSinglePath() {
        List<FusedChunk> fused = fuser.fuse(
                List.of(hit("policy-a#1", 12.0, 1), hit("policy-b#1", 9.0, 2)),
                List.of(hit("policy-b#1", 0.85, 1), hit("policy-c#1", 0.80, 2)));

        assertThat(fused).extracting(chunk -> chunk.meta().chunkId())
                .containsExactly("policy-b#1", "policy-a#1", "policy-c#1");
        // 两路都命中的片段：BM25 排第 2、向量排第 1，得分是两路倒数名次之和
        assertThat(fused.get(0).rrfScore()).isEqualTo(expectedScore(2) + expectedScore(1));
        assertThat(fused.get(0).rrfScore()).isGreaterThan(expectedScore(1));
    }

    @Test
    void singlePathHitsAreKeptWithoutPenalty() {
        List<FusedChunk> fused = fuser.fuse(
                List.of(hit("policy-a#1", 12.0, 1)),
                List.of(hit("policy-b#1", 0.85, 1)));

        assertThat(fused).hasSize(2);
        assertThat(fused).allSatisfy(chunk -> assertThat(chunk.rrfScore()).isEqualTo(expectedScore(1)));
        // 同分时用 BM25 原始分兜底，保证同样输入每次排序一致
        assertThat(fused.get(0).meta().chunkId()).isEqualTo("policy-a#1");
        assertThat(fused.get(0).vectorScore()).isNull();
        assertThat(fused.get(1).bm25Score()).isNull();
    }

    @Test
    void keepsRawScoresAndRanksForRefusalJudgementAndExplainability() {
        List<FusedChunk> fused = fuser.fuse(
                List.of(hit("policy-a#1", 12.5, 3)),
                List.of(hit("policy-a#1", 0.91, 2)));

        FusedChunk chunk = fused.get(0);
        assertThat(chunk.bm25Score()).isEqualTo(12.5);
        assertThat(chunk.bm25Rank()).isEqualTo(3);
        assertThat(chunk.vectorScore()).isEqualTo(0.91);
        assertThat(chunk.vectorRank()).isEqualTo(2);
        assertThat(chunk.rrfScore()).isEqualTo(expectedScore(3) + expectedScore(2));
    }

    @Test
    void rankBeatsRawScore() {
        // BM25 原始分高但名次靠后，融合后仍然输给两路都靠前的片段
        List<FusedChunk> fused = fuser.fuse(
                List.of(hit("policy-long#1", 30.0, 5), hit("policy-short#1", 8.0, 1)),
                List.of(hit("policy-short#1", 0.7, 2)));

        assertThat(fused.get(0).meta().chunkId()).isEqualTo("policy-short#1");
        assertThat(fused.get(1).meta().chunkId()).isEqualTo("policy-long#1");
    }

    @Test
    void emptyInputsProduceEmptyResult() {
        assertThat(fuser.fuse(List.of(), List.of())).isEmpty();
    }
}
