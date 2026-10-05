package com.ragwiki.rag.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.ragwiki.common.model.EmbeddingClient;
import com.ragwiki.rag.RagProperties;
import com.ragwiki.rag.split.ChunkSplitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 语料索引同步。
 *
 * <p>片段 id 由 doc_id 与片段序号拼成，重建时写的是同一批 id，所以重复执行不会产生重复数据。
 * 语料本身只读，这里不碰 corpus 下的任何文件。
 */
@Component
public class IndexSyncService {

    private static final Logger log = LoggerFactory.getLogger(IndexSyncService.class);

    private final ElasticsearchClient client;
    private final CorpusLoader corpusLoader;
    private final EmbeddingClient embeddingClient;
    private final IndexAdmin indexAdmin;
    private final RagProperties properties;

    public IndexSyncService(ElasticsearchClient client,
                            CorpusLoader corpusLoader,
                            EmbeddingClient embeddingClient,
                            IndexAdmin indexAdmin,
                            RagProperties properties) {
        this.client = client;
        this.corpusLoader = corpusLoader;
        this.embeddingClient = embeddingClient;
        this.indexAdmin = indexAdmin;
        this.properties = properties;
    }

    public record Report(String index, int documents, int chunks, long elapsedMillis) {
    }

    /** 重建索引：删掉物理索引重来，别名指到新索引上 */
    public Report rebuild() {
        long startedAt = System.currentTimeMillis();
        String physical = indexAdmin.physicalIndexName();
        indexAdmin.recreateIndex();

        List<SourceDocument> documents = corpusLoader.load();
        int chunks = 0;
        for (SourceDocument document : documents) {
            chunks += indexDocument(physical, document);
        }

        refresh(physical);
        long elapsed = System.currentTimeMillis() - startedAt;
        Report report = new Report(physical, documents.size(), chunks, elapsed);
        log.info("索引重建完成：{} 篇文档 / {} 个片段，耗时 {}ms", report.documents(), report.chunks(), elapsed);
        return report;
    }

    private int indexDocument(String physical, SourceDocument document) {
        RagProperties.Chunk options = properties.chunk();
        List<ChunkSplitter.Piece> pieces = ChunkSplitter.split(document.body(),
                new ChunkSplitter.Options(options.maxChars(), options.overlapChars()));
        if (pieces.isEmpty()) {
            return 0;
        }

        // 标题路径一起送进向量模型，让片段带上小节语义
        List<String> embeddingInputs = pieces.stream()
                .map(piece -> piece.headingPath() + "\n" + piece.content())
                .toList();
        List<float[]> vectors = embeddingClient.embedBatch(embeddingInputs);

        List<KnowledgeChunk> chunks = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            ChunkSplitter.Piece piece = pieces.get(i);
            chunks.add(new KnowledgeChunk(
                    chunkId(document.docId(), i),
                    document.docId(),
                    document.title(),
                    piece.headingPath(),
                    piece.content(),
                    document.category(),
                    document.version(),
                    document.status(),
                    document.effectiveAt(),
                    i,
                    vectors.get(i)));
        }
        writeBulk(physical, chunks);
        return chunks.size();
    }

    private void writeBulk(String physical, List<KnowledgeChunk> chunks) {
        BulkRequest.Builder builder = new BulkRequest.Builder();
        for (KnowledgeChunk chunk : chunks) {
            builder.operations(operation -> operation.index(index -> index
                    .index(physical)
                    .id(chunk.chunkId())
                    .document(chunk)));
        }

        BulkResponse response;
        try {
            response = client.bulk(builder.build());
        } catch (IOException e) {
            throw new UncheckedIOException("写入索引失败：" + physical, e);
        }
        if (response.errors()) {
            List<String> failures = response.items().stream()
                    .filter(item -> item.error() != null)
                    .map(item -> "%s -> %s".formatted(item.id(), item.error().reason()))
                    .limit(5)
                    .toList();
            throw new IllegalStateException("批量写入索引有失败项：" + failures);
        }
    }

    private void refresh(String physical) {
        try {
            client.indices().refresh(request -> request.index(physical));
        } catch (IOException e) {
            throw new UncheckedIOException("刷新索引失败：" + physical, e);
        }
    }

    private static String chunkId(String docId, int index) {
        return docId + "#" + index;
    }
}
