package com.ragwiki.rag.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import com.ragwiki.common.model.ModelProperties;
import com.ragwiki.rag.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 索引与别名的生命周期管理。
 *
 * <p>查询一律走别名，物理索引名带索引源与版本后缀（knowledge_doc_v1），
 * 切换索引源或改 mapping 时建新索引再改别名指向，不做原地改 mapping。
 */
@Component
public class IndexAdmin implements com.ragwiki.rag.api.IndexControlApi {

    private static final Logger log = LoggerFactory.getLogger(IndexAdmin.class);
    private static final String DIMS_PLACEHOLDER = "__DIMS__";
    private static final String VECTOR_FIELD = "embedding";

    private final ElasticsearchClient client;
    private final RagProperties properties;
    private final ModelProperties modelProperties;
    private final String mappingTemplate;

    public IndexAdmin(ElasticsearchClient client,
                      RagProperties properties,
                      ModelProperties modelProperties,
                      @Value("classpath:es/knowledge-index.json") Resource mappingResource) throws IOException {
        this.client = client;
        this.properties = properties;
        this.modelProperties = modelProperties;
        this.mappingTemplate = mappingResource.getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 启动时保证索引可用。
     * 维度不一致是配置错误，直接让启动失败；ES 连不上属于基础设施问题，只记日志不拦启动。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeOnStartup() {
        try {
            ensureIndex();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("初始化 ES 索引失败，应用继续启动，检索接口会报错", e);
        }
    }

    public void ensureIndex() {
        String physical = physicalIndexName();
        if (indexExists(physical)) {
            validateDimensions(physical);
        } else {
            int dims = configuredDimensions();
            if (dims <= 0) {
                log.warn("索引 {} 不存在，且 EMBEDDING_DIMENSIONS 未配置，跳过创建。配置好后调重建接口", physical);
                return;
            }
            createIndex(physical, dims);
            log.info("已创建索引 {}（dense_vector dims={}）", physical, dims);
        }
        ensureAlias(physical);
    }

    /**
     * 删除物理索引重建，用于换维度或换切分策略后重跑。
     * 别名先删后建，指到刚建好的索引上；别的物理索引不动，留着作对照组。
     */
    public void recreateIndex() {
        String physical = physicalIndexName();
        String alias = properties.index().alias();
        deleteAliasIfExists(alias);
        if (indexExists(physical)) {
            deleteIndex(physical);
            log.info("已删除索引 {}", physical);
        }
        createIndex(physical, requireConfiguredDimensions());
        createAlias(physical, alias);
    }

    public boolean indexExists() {
        return indexExists(physicalIndexName());
    }

    /** 索引缺失时抛错，而不是让检索静默返回空 */
    public void requireIndexReady() {
        String alias = properties.index().alias();
        try {
            boolean exists = client.indices().existsAlias(request -> request.name(alias)).value();
            if (!exists) {
                throw new IllegalStateException(
                        "索引别名 %s 不存在，先调 POST /api/rag/index/rebuild 建索引".formatted(alias));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("检查索引别名失败：" + alias, e);
        }
    }

    public long documentCount() {
        try {
            return client.count(request -> request.index(properties.index().alias())).count();
        } catch (IOException e) {
            throw new UncheckedIOException("统计索引文档数失败", e);
        }
    }

    public String physicalIndexName() {
        return properties.index().physicalIndexName();
    }

    public String alias() {
        return properties.index().alias();
    }

    // ---------- IndexControlApi ----------

    @Override
    public String currentAliasTarget() {
        String alias = properties.index().alias();
        try {
            Set<String> indices = client.indices().getAlias(request -> request.name(alias)).result().keySet();
            return indices.isEmpty() ? null : indices.iterator().next();
        } catch (IOException e) {
            throw new UncheckedIOException("读取别名指向失败：" + alias, e);
        }
    }

    @Override
    public void switchAlias(String physicalIndex) {
        if (!indexExists(physicalIndex)) {
            throw new IllegalStateException("物理索引不存在，无法切换别名：" + physicalIndex);
        }
        String alias = properties.index().alias();
        deleteAliasIfExists(alias);
        createAlias(physicalIndex, alias);
        log.info("别名 {} 已切换到 {}", alias, physicalIndex);
    }

    @Override
    public List<String> physicalIndices() {
        try {
            return client.indices().get(request -> request.index("knowledge_*"))
                    .result().keySet().stream().sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("列出物理索引失败", e);
        }
    }

    // ---------- 内部 ----------

    private boolean indexExists(String physical) {
        try {
            return client.indices().exists(request -> request.index(physical)).value();
        } catch (IOException e) {
            throw new UncheckedIOException("检查索引是否存在失败：" + physical, e);
        }
    }

    private void createIndex(String physical, int dims) {
        String mapping = mappingTemplate.replace(DIMS_PLACEHOLDER, String.valueOf(dims));
        try {
            client.indices().create(request -> request.index(physical).withJson(new StringReader(mapping)));
        } catch (IOException e) {
            throw new UncheckedIOException("创建索引失败：" + physical, e);
        }
    }

    private void deleteIndex(String physical) {
        try {
            client.indices().delete(request -> request.index(physical));
        } catch (IOException e) {
            throw new UncheckedIOException("删除索引失败：" + physical, e);
        }
    }

    private void ensureAlias(String physical) {
        String alias = properties.index().alias();
        try {
            boolean exists = client.indices().existsAlias(request -> request.name(alias)).value();
            if (exists) {
                Set<String> indices = client.indices().getAlias(request -> request.name(alias)).result().keySet();
                if (!indices.contains(physical)) {
                    log.warn("别名 {} 当前指向 {}，不是 {}，不做改动（切换索引源时应由运维显式改指向）",
                            alias, indices, physical);
                }
                return;
            }
            createAlias(physical, alias);
        } catch (IOException e) {
            throw new UncheckedIOException("创建索引别名失败：" + alias, e);
        }
    }

    private void createAlias(String physical, String alias) {
        try {
            client.indices().updateAliases(request -> request.actions(action -> action.add(add -> add
                    .index(physical)
                    .alias(alias)
                    .isWriteIndex(true))));
            log.info("已创建别名 {} -> {}", alias, physical);
        } catch (IOException e) {
            throw new UncheckedIOException("创建索引别名失败：" + alias, e);
        }
    }

    private void deleteAliasIfExists(String alias) {
        try {
            if (client.indices().existsAlias(request -> request.name(alias)).value()) {
                // 通配符表示把这个别名从所有索引上摘掉，这个客户端版本要求必须带 index
                client.indices().deleteAlias(request -> request.index("*").name(alias));
                log.info("已移除别名 {}", alias);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("移除索引别名失败：" + alias, e);
        }
    }

    /** dense_vector 的维度必须与 EMBEDDING_DIMENSIONS 一致，不一致直接报错，别等到检索时才出问题 */
    private void validateDimensions(String physical) {
        int configured = configuredDimensions();
        int indexed = indexedDimensions(physical);
        if (configured > 0 && indexed > 0 && configured != indexed) {
            throw new IllegalStateException(
                    "索引 %s 的 dense_vector dims=%d，与 EMBEDDING_DIMENSIONS=%d 不一致。改了 embedding 模型或维度必须重建索引"
                            .formatted(physical, indexed, configured));
        }
    }

    private int indexedDimensions(String physical) {
        GetMappingResponse response;
        try {
            response = client.indices().getMapping(request -> request.index(physical));
        } catch (IOException e) {
            throw new UncheckedIOException("读取索引 mapping 失败：" + physical, e);
        }
        IndexMappingRecord record = response.result().get(physical);
        if (record == null) {
            return -1;
        }
        Property property = record.mappings().properties().get(VECTOR_FIELD);
        if (property == null || !property.isDenseVector()) {
            return -1;
        }
        return property.denseVector().dims();
    }

    private int configuredDimensions() {
        ModelProperties.Embedding embedding = modelProperties.embedding();
        if (embedding == null || embedding.dimensions() == null) {
            return -1;
        }
        return embedding.dimensions();
    }

    private int requireConfiguredDimensions() {
        int dims = configuredDimensions();
        if (dims <= 0) {
            throw new IllegalStateException("EMBEDDING_DIMENSIONS 未配置，无法建索引");
        }
        return dims;
    }
}
