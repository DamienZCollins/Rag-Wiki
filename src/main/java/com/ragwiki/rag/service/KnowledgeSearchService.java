package com.ragwiki.rag.service;

import com.ragwiki.rag.RagProperties;
import com.ragwiki.rag.api.KnowledgeSearchApi;
import com.ragwiki.rag.dto.ChunkHit;
import com.ragwiki.rag.dto.FusedChunk;
import com.ragwiki.rag.dto.SearchOutcome;
import com.ragwiki.rag.index.IndexAdmin;
import com.ragwiki.rag.search.Bm25Searcher;
import com.ragwiki.rag.search.RefusalJudge;
import com.ragwiki.rag.search.RrfFuser;
import com.ragwiki.rag.search.VectorSearcher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索引擎：两路召回 → 拒答判定 → RRF 融合 → 取 topK。
 * 拒答判定放在融合之前，因为阈值是单路原始分的事，融合后就没有原始分了。
 */
@Service
public class KnowledgeSearchService implements KnowledgeSearchApi {

    private final Bm25Searcher bm25Searcher;
    private final VectorSearcher vectorSearcher;
    private final RrfFuser rrfFuser;
    private final RefusalJudge refusalJudge;
    private final IndexAdmin indexAdmin;
    private final RagProperties properties;

    public KnowledgeSearchService(Bm25Searcher bm25Searcher,
                                  VectorSearcher vectorSearcher,
                                  RrfFuser rrfFuser,
                                  RefusalJudge refusalJudge,
                                  IndexAdmin indexAdmin,
                                  RagProperties properties) {
        this.bm25Searcher = bm25Searcher;
        this.vectorSearcher = vectorSearcher;
        this.rrfFuser = rrfFuser;
        this.refusalJudge = refusalJudge;
        this.indexAdmin = indexAdmin;
        this.properties = properties;
    }

    /** 带完整中间结果的检索，给 HTTP 接口和验收用 */
    public SearchOutcome searchDetailed(String question) {
        indexAdmin.requireIndexReady();

        int window = properties.candidateWindow();
        List<ChunkHit> bm25Hits = bm25Searcher.search(question, window);
        List<ChunkHit> vectorHits = vectorSearcher.search(question, window);

        double topBm25 = RefusalJudge.topScore(bm25Hits);
        double topVector = RefusalJudge.topScore(vectorHits);

        if (refusalJudge.shouldRefuse(bm25Hits, vectorHits)) {
            return new SearchOutcome(question, List.of(), true, topBm25, topVector);
        }

        List<FusedChunk> fused = rrfFuser.fuse(bm25Hits, vectorHits);
        int topK = Math.min(properties.topK(), fused.size());
        return new SearchOutcome(question, List.copyOf(fused.subList(0, topK)), false, topBm25, topVector);
    }

    @Override
    public KnowledgeSearchOutcome search(String question) {
        SearchOutcome outcome = searchDetailed(question);
        if (outcome.refused()) {
            return KnowledgeSearchOutcome.miss();
        }
        List<KnowledgeSearchOutcome.Citation> citations = new ArrayList<>(outcome.chunks().size());
        for (FusedChunk chunk : outcome.chunks()) {
            citations.add(new KnowledgeSearchOutcome.Citation(
                    chunk.meta().chunkId(),
                    chunk.meta().docId(),
                    chunk.meta().title(),
                    chunk.meta().version(),
                    chunk.meta().headingPath(),
                    chunk.meta().content()));
        }
        return new KnowledgeSearchOutcome(true, List.copyOf(citations));
    }
}
