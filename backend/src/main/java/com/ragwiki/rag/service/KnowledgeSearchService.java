package com.ragwiki.rag.service;

import com.ragwiki.common.exception.BusinessException;
import com.ragwiki.common.result.ResultCode;
import com.ragwiki.rag.RagProperties;
import com.ragwiki.rag.api.KnowledgeProbeApi;
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
 *
 * <p>两路都召回为空表示索引里没有可召回文档，抛 {@link ResultCode#RAG_INDEX_EMPTY}，
 * 与「两路分数都低」的超范围拒答区分开。
 */
@Service
public class KnowledgeSearchService implements KnowledgeSearchApi, KnowledgeProbeApi {

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
        return searchDetailed(question, null);
    }

    /**
     * @param indexOverride 指定物理索引，用于索引源对照实验；为空时走别名
     */
    public SearchOutcome searchDetailed(String question, String indexOverride) {
        FullOutcome full = searchFull(question, indexOverride);
        if (full.refused()) {
            return new SearchOutcome(question, List.of(), true, full.topBm25Score(), full.topVectorScore());
        }
        List<FusedChunk> fused = full.fused();
        int topK = Math.min(properties.topK(), fused.size());
        return new SearchOutcome(question, List.copyOf(fused.subList(0, topK)), false,
                full.topBm25Score(), full.topVectorScore());
    }

    /**
     * 融合后不截断的完整结果。检索探针用它区分「没召回」与「召回了但排在生效位次之外」：
     * 前者是召回层的问题，后者是排序层的问题，截断之后这两个就分不开了。
     */
    private FullOutcome searchFull(String question, String indexOverride) {
        if (indexOverride == null) {
            indexAdmin.requireIndexReady();
        }

        int window = properties.candidateWindow();
        List<ChunkHit> bm25Hits = bm25Searcher.search(question, window, indexOverride);
        List<ChunkHit> vectorHits = vectorSearcher.search(question, window, indexOverride);

        // 两路都空说明索引里没有可召回文档，不当超范围拒答，按索引问题暴露出来
        if (bm25Hits.isEmpty() && vectorHits.isEmpty()) {
            throw new BusinessException(ResultCode.RAG_INDEX_EMPTY);
        }

        double topBm25 = RefusalJudge.topScore(bm25Hits);
        double topVector = RefusalJudge.topScore(vectorHits);

        if (refusalJudge.shouldRefuse(bm25Hits, vectorHits)) {
            return new FullOutcome(List.of(), true, topBm25, topVector);
        }
        return new FullOutcome(rrfFuser.fuse(bm25Hits, vectorHits), false, topBm25, topVector);
    }

    private record FullOutcome(List<FusedChunk> fused, boolean refused,
                               double topBm25Score, double topVectorScore) {
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
                    chunk.meta().pageId(),
                    chunk.meta().sourceDocs(),
                    chunk.meta().title(),
                    chunk.meta().version(),
                    chunk.meta().headingPath(),
                    chunk.meta().content()));
        }
        return new KnowledgeSearchOutcome(true, List.copyOf(citations));
    }

    @Override
    public ProbeResult probe(String question) {
        FullOutcome full = searchFull(question, null);
        List<ProbeChunk> chunks = new ArrayList<>(full.fused().size());
        for (FusedChunk chunk : full.fused()) {
            chunks.add(new ProbeChunk(
                    chunk.meta().chunkId(),
                    chunk.meta().docId(),
                    chunk.meta().pageId(),
                    chunk.meta().sourceDocs(),
                    chunk.meta().title(),
                    chunk.meta().headingPath(),
                    chunk.meta().content(),
                    chunk.bm25Score(),
                    chunk.vectorScore(),
                    chunk.rrfScore()));
        }
        return new ProbeResult(full.refused(), full.topBm25Score(), full.topVectorScore(),
                properties.candidateWindow(), properties.topK(), List.copyOf(chunks));
    }
}
