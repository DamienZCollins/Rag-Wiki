package com.ragwiki.rag.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.ragwiki.common.model.EmbeddingClient;
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
 * 向量语义检索。query 走 embedding，检索 dense_vector 字段。
 * 与 BM25 用同一套状态过滤，无向量的片段自然不会出现在结果里。
 */
@Component
public class VectorSearcher {

    private static final String ACTIVE = "active";
    private static final String VECTOR_FIELD = "embedding";
    private static final int MIN_CANDIDATES = 100;

    private final ElasticsearchClient client;
    private final EmbeddingClient embeddingClient;
    private final RagProperties properties;

    public VectorSearcher(ElasticsearchClient client, EmbeddingClient embeddingClient, RagProperties properties) {
        this.client = client;
        this.embeddingClient = embeddingClient;
        this.properties = properties;
    }

    public List<ChunkHit> search(String question, int window) {
        float[] queryVector = embeddingClient.embed(question);
        List<Float> vector = new ArrayList<>(queryVector.length);
        for (float value : queryVector) {
            vector.add(value);
        }

        // kNN 的候选池要比返回条数大，否则近邻图搜索会漏
        int candidates = Math.max(window * 2, MIN_CANDIDATES);
        SearchResponse<KnowledgeChunk> response = execute(vector, window, candidates);

        List<ChunkHit> hits = new ArrayList<>();
        int rank = 1;
        for (var hit : response.hits().hits()) {
            if (hit.source() == null) {
                continue;
            }
            // kNN 命中的 _score 就是相似度本身，可以直接当原始分用于拒答判定
            hits.add(new ChunkHit(ChunkMeta.from(hit.source()), hit.score() == null ? 0 : hit.score(), rank++));
        }
        return hits;
    }

    private SearchResponse<KnowledgeChunk> execute(List<Float> vector, int window, int candidates) {
        try {
            return client.search(request -> request
                            .index(properties.index().alias())
                            .size(window)
                            .knn(knn -> knn
                                    .field(VECTOR_FIELD)
                                    .queryVector(vector)
                                    .k(window)
                                    .numCandidates(candidates)
                                    .filter(filter -> filter.term(term -> term
                                            .field("status")
                                            .value(ACTIVE)))),
                    KnowledgeChunk.class);
        } catch (IOException e) {
            throw new UncheckedIOException("向量检索失败", e);
        }
    }
}
