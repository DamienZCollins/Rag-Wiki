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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拒答这条路径的端到端验证。
 *
 * <p>把两路阈值抬到任何分数都够不着的位置，于是必然拒答——这样验的是**拒答机制**本身：
 * 命中为空、响应仍是 200、句子照常返回给调用方判断。
 *
 * <p>为什么不用一个「看起来超范围」的问题来验：桩向量是按字符哈希出来的，
 * 它给无关文本的相似度并不低（实测英文问句能拿到 0.57），拿它验语义阈值只会得出错误结论。
 * 阈值定得对不对，只能拿真 embedding 在真实问题上标定，见阶段 3 验收记录。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RagRefusalIntegrationTest {

    private static StubModelServer stub;

    @DynamicPropertySource
    static void highThresholds(DynamicPropertyRegistry registry) throws IOException {
        stub = new StubModelServer();
        registry.add("model.chat.base-url", stub::baseUrl);
        registry.add("model.chat.api-key", () -> "stub-key");
        registry.add("model.chat.model", () -> "stub-model");
        registry.add("model.embedding.base-url", stub::baseUrl);
        registry.add("model.embedding.api-key", () -> "stub-key");
        registry.add("model.embedding.model", () -> "stub-embedding");
        // 走 Wiki 条目索引：这批用例只需要「能跑通重建与检索」，顺带覆盖另一条索引源
        registry.add("rag.index.source", () -> "wiki");
        // 余弦相似度最大是 1，BM25 分理论上无上界，两个阈值都抬到够不着
        registry.add("rag.refuse.bm25-threshold", () -> 1_000_000.0);
        registry.add("rag.refuse.vector-threshold", () -> 1.5);
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
    void refusesAndReturnsEmptyHits() throws Exception {
        rebuild();
        JsonNode response = search("退货运费谁出");

        assertThat(response.get("refused").asBoolean()).isTrue();
        assertThat(response.get("chunks")).isEmpty();
        // 拒答是正常结果，不是错误：两路原始分照样带出来，方便看是被卡在哪条线上
        assertThat(response.get("topBm25Score").asDouble()).isGreaterThan(0);
        assertThat(response.get("topVectorScore").asDouble()).isGreaterThan(0);
        assertThat(response.get("question").asText()).isEqualTo("退货运费谁出");
    }

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
}
