package com.ragwiki.rag.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.ragwiki.rag.RagProperties;
import com.ragwiki.rag.dto.ChunkHit;
import com.ragwiki.rag.dto.ChunkMeta;
import com.ragwiki.rag.index.KnowledgeChunk;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 关键词检索。正文、标题路径、文档标题一起参与匹配，标题权重更高。
 * 固定过滤 status，已失效的政策不参与召回。
 */
@Component
public class Bm25Searcher {

    /** 已失效的文档不召回 */
    private static final String ACTIVE = "active";

    private final ElasticsearchClient client;
    private final RagProperties properties;

    public Bm25Searcher(ElasticsearchClient client, RagProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public List<ChunkHit> search(String question, int window) {
        return search(question, window, null);
    }

    /**
     * @param indexOverride 指定物理索引，用于索引源对照实验；为空时走别名
     */
    public List<ChunkHit> search(String question, int window, String indexOverride) {
        SearchResponse<KnowledgeChunk> response = execute(question, window, indexOverride);

        List<ChunkHit> hits = new ArrayList<>();
        int rank = 1;
        for (var hit : response.hits().hits()) {
            if (hit.source() == null) {
                continue;
            }
            hits.add(new ChunkHit(ChunkMeta.from(hit.source()), hit.score() == null ? 0 : hit.score(), rank++));
        }
        return hits;
    }

    private SearchResponse<KnowledgeChunk> execute(String question, int window, String indexOverride) {
        String index = indexOverride == null ? properties.index().alias() : indexOverride;
        try {
            return client.search(request -> request
                            .index(index)
                            .size(window)
                            .query(query -> query.bool(bool -> bool
                                    .must(must -> must.multiMatch(multi -> multi
                                            .query(question)
                                            .fields("content", "heading_path^1.5", "title^2.0")))
                                    .filter(filter -> filter.term(term -> term
                                            .field("status")
                                            .value(ACTIVE))))),
                    KnowledgeChunk.class);
        } catch (IOException e) {
            throw new UncheckedIOException("BM25 检索失败", e);
        }
    }
}
