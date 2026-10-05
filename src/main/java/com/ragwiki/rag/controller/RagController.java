package com.ragwiki.rag.controller;

import com.ragwiki.common.result.Result;
import com.ragwiki.rag.dto.FusedChunk;
import com.ragwiki.rag.dto.RagSearchRequest;
import com.ragwiki.rag.dto.SearchOutcome;
import com.ragwiki.rag.dto.SearchResponse;
import com.ragwiki.rag.index.IndexSyncService;
import com.ragwiki.rag.service.KnowledgeSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@Tag(name = "RAG", description = "BM25 + 向量双路召回，RRF 融合，带引用与拒答")
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final KnowledgeSearchService searchService;
    private final IndexSyncService indexSyncService;

    public RagController(KnowledgeSearchService searchService, IndexSyncService indexSyncService) {
        this.searchService = searchService;
        this.indexSyncService = indexSyncService;
    }

    @Operation(summary = "检索知识库", description = """
            两路召回后按 RRF 融合。返回里同时给出两路的原始分与名次，
            拒答判定用的是原始分（融合分只反映名次，没有绝对量纲）。""")
    @PostMapping("/search")
    public Result<SearchResponse> search(@Valid @RequestBody RagSearchRequest request) {
        SearchOutcome outcome = searchService.searchDetailed(request.question(), request.index());

        List<SearchResponse.Chunk> chunks = new ArrayList<>(outcome.chunks().size());
        for (FusedChunk chunk : outcome.chunks()) {
            chunks.add(new SearchResponse.Chunk(
                    chunk.meta().chunkId(),
                    chunk.meta().docId(),
                    chunk.meta().pageId(),
                    chunk.meta().sourceDocs(),
                    chunk.meta().title(),
                    chunk.meta().headingPath(),
                    chunk.meta().content(),
                    chunk.meta().category(),
                    chunk.meta().version(),
                    chunk.bm25Score(),
                    chunk.bm25Rank(),
                    chunk.vectorScore(),
                    chunk.vectorRank(),
                    chunk.rrfScore()));
        }
        return Result.ok(new SearchResponse(
                outcome.question(), outcome.refused(),
                outcome.topBm25Score(), outcome.topVectorScore(), chunks));
    }

    @Operation(summary = "重建索引", description = "离线批处理。删掉物理索引重新切分、向量化、写入，可重复执行")
    @PostMapping("/index/rebuild")
    public Result<IndexSyncService.Report> rebuild() {
        return Result.ok(indexSyncService.rebuild());
    }
}
