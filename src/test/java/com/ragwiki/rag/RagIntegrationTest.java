package com.ragwiki.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragwiki.support.StubModelServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索链路的端到端验证：真实 ES、真实 MySQL、真实 HTTP，只有模型服务用桩。
 *
 * <p>桩向量是按字符哈希出来的，词汇重叠多的文本才更近，足以验证「双路召回 + RRF 融合 + 拒答 + 引用」
 * 这条链路的正确性，但**不能**用来验收语义质量——那必须用真 embedding 端点。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RagIntegrationTest {

    private static StubModelServer stub;

    @DynamicPropertySource
    static void modelProperties(DynamicPropertyRegistry registry) throws IOException {
        stub = new StubModelServer();
        registry.add("model.chat.base-url", stub::baseUrl);
        registry.add("model.chat.api-key", () -> "stub-key");
        registry.add("model.chat.model", () -> "stub-model");
        registry.add("model.embedding.base-url", stub::baseUrl);
        registry.add("model.embedding.api-key", () -> "stub-key");
        registry.add("model.embedding.model", () -> "stub-embedding");

        // 拒答阈值放宽到「只挡住完全没有信号的问题」。
        // 桩向量是按字符哈希出来的，分数分布和真模型完全不同，
        // 拿真模型标定出来的阈值（BM25 8.0 / 向量 0.72）跑这批用例只会验错东西。
        // 阈值的正确性由 RefusalJudgeTest 覆盖规则、由真模型上的手工验收覆盖标定。
        registry.add("rag.refuse.bm25-threshold", () -> 1.0);
        registry.add("rag.refuse.vector-threshold", () -> 0.05);
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.close();
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void rebuildsIndexIdempotently() throws Exception {
        JsonNode first = rebuild();
        assertThat(first.get("documents").asInt()).isEqualTo(14);
        assertThat(first.get("chunks").asInt()).isGreaterThan(14);

        JsonNode second = rebuild();
        // 片段 id 由 doc_id 与序号拼成，重建写的是同一批 id，不会翻倍
        assertThat(second.get("chunks").asInt()).isEqualTo(first.get("chunks").asInt());
        assertThat(second.get("index").asText()).isEqualTo(first.get("index").asText());
    }

    @Test
    void keywordQueryHitsExpectedPolicy() throws Exception {
        rebuild();
        JsonNode response = search("运费险");

        assertThat(response.get("refused").asBoolean()).isFalse();
        List<String> docIds = docIds(response);
        assertThat(docIds).contains("policy-freight-insurance");
    }

    @Test
    void colloquialQueryHitsFreightPolicy() throws Exception {
        rebuild();
        JsonNode response = search("退货的邮费谁掏");

        assertThat(response.get("refused").asBoolean()).isFalse();
        assertThat(docIds(response)).anyMatch(docId -> docId.startsWith("policy-freight"));
    }

    @Test
    void fusedResultCarriesBothPathsScoresAndRanks() throws Exception {
        rebuild();
        JsonNode response = search("退货运费谁承担");

        JsonNode chunks = response.get("chunks");
        assertThat(chunks.size()).isGreaterThan(0);

        boolean anyHitByBothPaths = false;
        for (JsonNode chunk : chunks) {
            if (chunk.hasNonNull("bm25Score") && chunk.hasNonNull("vectorScore")) {
                anyHitByBothPaths = true;
                assertThat(chunk.get("bm25Rank").asInt()).isGreaterThan(0);
                assertThat(chunk.get("vectorRank").asInt()).isGreaterThan(0);
            }
            assertThat(chunk.get("rrfScore").asDouble()).isGreaterThan(0);
        }
        assertThat(anyHitByBothPaths)
                .as("至少要有一个片段被两路同时命中，否则 RRF 融合没有实际发生")
                .isTrue();
    }

    @Test
    void retiredDocumentIsFilteredOut() throws Exception {
        rebuild();
        // 已失效的 2024 版运费规则里有「包邮门槛 79 元」，现行版是 99 元
        JsonNode response = search("包邮门槛多少");

        assertThat(docIds(response)).doesNotContain("policy-freight-rule-v2024");
    }

    @Test
    void citationsAreTraceable() throws Exception {
        rebuild();
        JsonNode response = search("发票开错了怎么换开");

        assertThat(response.get("refused").asBoolean()).isFalse();
        JsonNode chunk = response.get("chunks").get(0);
        assertThat(chunk.get("chunkId").asText()).contains("#");
        assertThat(chunk.get("docId").asText()).startsWith("policy-");
        assertThat(chunk.get("title").asText()).isNotBlank();
        assertThat(chunk.get("headingPath").asText()).isNotBlank();
        assertThat(chunk.get("content").asText()).isNotBlank();
    }

    // ---------- 辅助 ----------

    private JsonNode rebuild() throws Exception {
        return post("/api/rag/index/rebuild", null);
    }

    private JsonNode search(String question) throws Exception {
        return post("/api/rag/search", objectMapper.writeValueAsString(new SearchBody(question)));
    }

    private record SearchBody(String question) {
    }

    private JsonNode post(String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5));
        builder.POST(body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).as("响应体：%s", response.body()).isEqualTo(200);
        JsonNode envelope = objectMapper.readTree(response.body());
        assertThat(envelope.get("code").asInt()).isEqualTo(200);
        return envelope.get("data");
    }

    private List<String> docIds(JsonNode searchData) {
        List<String> docIds = new ArrayList<>();
        for (JsonNode chunk : searchData.get("chunks")) {
            docIds.add(chunk.get("docId").asText());
        }
        return docIds;
    }
}
