package com.ragwiki.rag.service;

import com.ragwiki.common.exception.BusinessException;
import com.ragwiki.common.result.ResultCode;
import com.ragwiki.rag.RagProperties;
import com.ragwiki.rag.dto.ChunkHit;
import com.ragwiki.rag.dto.ChunkMeta;
import com.ragwiki.rag.dto.SearchOutcome;
import com.ragwiki.rag.index.IndexAdmin;
import com.ragwiki.rag.search.Bm25Searcher;
import com.ragwiki.rag.search.RefusalJudge;
import com.ragwiki.rag.search.RrfFuser;
import com.ragwiki.rag.search.VectorSearcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 检索引擎的两个出口：两路都召回为空按索引问题报错，只有一路为空仍按分数判定。
 */
class KnowledgeSearchServiceTest {

    private static final double BM25_THRESHOLD = 8.0;
    private static final double VECTOR_THRESHOLD = 0.72;

    private final Bm25Searcher bm25Searcher = mock(Bm25Searcher.class);
    private final VectorSearcher vectorSearcher = mock(VectorSearcher.class);
    private final RrfFuser rrfFuser = mock(RrfFuser.class);
    private final IndexAdmin indexAdmin = mock(IndexAdmin.class);

    private final RagProperties properties = new RagProperties(null, null, null, 50, 8,
            new RagProperties.Refuse(BM25_THRESHOLD, VECTOR_THRESHOLD));

    private final KnowledgeSearchService service = new KnowledgeSearchService(
            bm25Searcher, vectorSearcher, rrfFuser, new RefusalJudge(properties), indexAdmin, properties);

    @Test
    void reportsIndexProblemWhenBothPathsReturnNothing() {
        givenHits(List.of(), List.of());

        assertThatThrownBy(() -> service.searchDetailed("退货运费谁出"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(ResultCode.RAG_INDEX_EMPTY));
    }

    @Test
    void keepsJudgingByScoreWhenOnlyOnePathReturnsNothing() {
        // 关键词一路空、向量一路够高，是口语化问法的正常形态，不该按索引问题处理
        givenHits(List.of(), hits(0.83));

        SearchOutcome outcome = service.searchDetailed("退货运费谁出");

        assertThat(outcome.refused()).isFalse();
    }

    @Test
    void refusesWhenBothPathsStayBelowThreshold() {
        givenHits(hits(3.0), hits(0.65));

        SearchOutcome outcome = service.searchDetailed("退货运费谁出");

        assertThat(outcome.refused()).isTrue();
        assertThat(outcome.chunks()).isEmpty();
        assertThat(outcome.topBm25Score()).isEqualTo(3.0);
        assertThat(outcome.topVectorScore()).isEqualTo(0.65);
    }

    private void givenHits(List<ChunkHit> bm25Hits, List<ChunkHit> vectorHits) {
        when(bm25Searcher.search(anyString(), anyInt(), any())).thenReturn(bm25Hits);
        when(vectorSearcher.search(anyString(), anyInt(), any())).thenReturn(vectorHits);
        when(rrfFuser.fuse(any(), any())).thenReturn(List.of());
    }

    private static List<ChunkHit> hits(double score) {
        return List.of(new ChunkHit(
                new ChunkMeta("c#0", "doc", null, List.of("doc"), "标题", "小节", "正文", "类目", "v1"),
                score, 1));
    }
}
