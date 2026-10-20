package com.ragwiki.wiki;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragwiki.support.StubModelServer;
import com.ragwiki.wiki.mapper.FastEntryMapper;
import com.ragwiki.wiki.mapper.QueryLogMapper;
import com.ragwiki.wiki.mapper.WikiPageMapper;
import com.ragwiki.wiki.schema.FastEntry;
import com.ragwiki.wiki.schema.FastEntryStatus;
import com.ragwiki.wiki.schema.PageContent;
import com.ragwiki.wiki.schema.QueryLog;
import com.ragwiki.wiki.schema.WikiPage;
import com.ragwiki.wiki.schema.WikiPageStatus;
import com.ragwiki.wiki.store.GitPageStore;
import com.ragwiki.wiki.store.WikiStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快通道全链路：派生 → 发布 → 命中直答 → 来源失效后下线。
 *
 * <p>场景名每次不同，避免与库里的真实条目混在一起；
 * 派生过程零模型调用，桩只用来满足上下文的模型客户端装配。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FastPathIntegrationTest {

    private static StubModelServer stub;

    @TempDir
    static Path workDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        stub = new StubModelServer();
        registry.add("model.chat.base-url", stub::baseUrl);
        registry.add("model.chat.api-key", () -> "stub-key");
        registry.add("model.chat.model", () -> "stub-model");
        registry.add("model.embedding.base-url", stub::baseUrl);
        registry.add("model.embedding.api-key", () -> "stub-key");
        registry.add("model.embedding.model", () -> "stub-embedding");
        registry.add("wiki.page-dir", () -> workDir.resolve("pages").toString());
        // 派生与作答都只认配置里的场景，用独立场景隔离库里的真实条目
        registry.add("fastpath.scene", () -> SCENE);
    }

    private static final String SCENE = "it-" + UUID.randomUUID().toString().substring(0, 8);

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

    @Autowired
    private WikiStore store;

    @Autowired
    private GitPageStore pageStore;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private FastEntryMapper fastEntryMapper;

    @Autowired
    private QueryLogMapper queryLogMapper;

    private String pageId;

    @BeforeEach
    void setUp() {
        pageId = null;
    }

    @AfterEach
    void cleanUp() {
        if (pageId != null) {
            wikiPageMapper.delete(Wrappers.<WikiPage>lambdaQuery().eq(WikiPage::getPageId, pageId));
        }
        fastEntryMapper.delete(Wrappers.<FastEntry>lambdaQuery().eq(FastEntry::getScene, SCENE));
        queryLogMapper.delete(Wrappers.<QueryLog>lambdaQuery().eq(QueryLog::getChannel, "FASTPATH")
                .like(QueryLog::getQuestion, "运费补贴上限是多少"));
    }

    @Test
    void derivesPublishesAndAnswersWithoutModelCalls() throws Exception {
        String title = "运费补贴上限";
        String summary = "质量问题退货运费单笔最高补贴 20 元，超出部分由买家承担。";
        pageId = createPage(title, summary, WikiPageStatus.ACTIVE);

        JsonNode deriveReport = post("/api/fastpath/entries/derive", null);
        assertThat(deriveReport.get("created").asInt()).isPositive();

        FastEntry candidate = entryOf(title);
        assertThat(candidate.getStatus()).isEqualTo(FastEntryStatus.CANDIDATE);
        assertThat(candidate.getSourcePageId()).isEqualTo(pageId);

        JsonNode publishReport = post("/api/fastpath/entries/publish", null);
        assertThat(publishReport.get("published").asInt()).isPositive();
        assertThat(entryOf(title).getStatus()).isEqualTo(FastEntryStatus.ACTIVE);

        // 候选列表走 Redis 缓存，发布接口已经清过一次，这里应当能看到新上线的条目
        JsonNode entries = get("/api/fastpath/entries?scene=" + SCENE);
        assertThat(entries.toString()).contains("运费补贴上限是多少");

        // 关键词命中：问答结果带来源标题，链路不经过模型
        JsonNode answer = post("/api/fastpath/answer",
                "{\"question\":\"运费补贴上限是多少？\"}");
        assertThat(answer.get("hit").asBoolean()).isTrue();
        assertThat(answer.get("answer").asText()).isEqualTo(summary);
        assertThat(answer.get("source").asText()).isEqualTo(title);
        assertThat(answer.get("entryId").asText()).isEqualTo(candidate.getEntryId());
    }

    @Test
    void publishOfflinesEntryWhenSourcePageRetired() throws Exception {
        String title = "运费补贴上限";
        pageId = createPage(title, "质量问题退货运费单笔最高补贴 20 元，超出部分由买家承担。",
                WikiPageStatus.ACTIVE);

        post("/api/fastpath/entries/derive", null);
        post("/api/fastpath/entries/publish", null);
        assertThat(entryOf(title).getStatus()).isEqualTo(FastEntryStatus.ACTIVE);

        // 来源条目失效后，再次上线校验必须把生效条目下线
        WikiPage page = store.findPage(pageId).orElseThrow();
        page.retire("it-source-retired", Instant.now());
        store.updatePage(page);

        JsonNode publishReport = post("/api/fastpath/entries/publish", null);

        assertThat(publishReport.get("offlined").asInt()).isPositive();
        assertThat(publishReport.get("offlinedEntries").toString()).contains("运费补贴上限是多少");
        assertThat(entryOf(title).getStatus()).isEqualTo(FastEntryStatus.OFFLINE);

        // 下线后不再出现在候选列表，作答也不再命中
        JsonNode entries = get("/api/fastpath/entries?scene=" + SCENE);
        assertThat(entries.toString()).doesNotContain("运费补贴上限是多少");
        JsonNode answer = post("/api/fastpath/answer", "{\"question\":\"运费补贴上限是多少？\"}");
        assertThat(answer.get("hit").asBoolean()).isFalse();
    }

    @Test
    void publishRejectsCandidateWithoutSource() throws Exception {
        // 沉淀出来的条目没有来源，只能停在候选态
        FastEntry distilled = new FastEntry();
        distilled.setEntryId("fp-it-" + UUID.randomUUID().toString().substring(0, 8));
        distilled.setScene(SCENE);
        distilled.setQuestion("生鲜坏了怎么赔？");
        distilled.setAnswer("生鲜类商品坏损需要拍照举证，审核通过后退款。");
        distilled.setKeywords(FastEntry.keywordsJson(java.util.List.of("生鲜")));
        distilled.setConfidence(java.math.BigDecimal.valueOf(0.6));
        distilled.setStatus(FastEntryStatus.CANDIDATE);
        distilled.setOrigin(FastEntry.ORIGIN_DISTILLED);
        distilled.setHitCount(0);
        Instant now = Instant.now();
        distilled.setCreatedAt(now);
        distilled.setUpdatedAt(now);
        fastEntryMapper.insert(distilled);

        JsonNode report = post("/api/fastpath/entries/publish", null);

        assertThat(report.get("rejected").asInt()).isPositive();
        assertThat(report.get("rejections").toString()).contains("没有来源 Wiki 条目");
        assertThat(store.findFastEntry(distilled.getEntryId()).orElseThrow().getStatus())
                .isEqualTo(FastEntryStatus.CANDIDATE);
    }

    // ---------- 辅助 ----------

    private String createPage(String title, String summary, WikiPageStatus status) {
        WikiPage page = new WikiPage();
        String id = WikiPage.pageIdOf(title + "-" + SCENE);
        page.setPageId(id);
        page.setTitle(title);
        page.setVersion("v2025.1");
        page.setStatus(status);
        page.setSources(WikiPage.sourcesJson(java.util.List.of("it-fastpath-source")));
        page.setInputHash("it-fastpath-input-hash");
        page.setEffectiveAt(java.time.LocalDate.of(2025, 1, 1));
        Instant now = Instant.now();
        page.setCreatedAt(now);
        page.setUpdatedAt(now);
        store.insertPage(page);
        pageStore.write(page, new PageContent(summary, java.util.List.of(), java.util.List.of(),
                java.util.List.of()));

        // 派生按标题套问句模板，条目 id 只由问题决定；场景隔离靠 scene 字段
        return id;
    }

    /** 按场景取刚派生的那一条。库里可能有同名条目，这里只看本用例的场景 */
    private FastEntry entryOf(String title) {
        return store.listFastEntries(SCENE, FastEntryStatus.CANDIDATE).stream()
                .filter(entry -> entry.getSourcePageId() != null && entry.getSourcePageId().equals(pageId))
                .findFirst()
                .or(() -> store.listFastEntries(SCENE, FastEntryStatus.ACTIVE).stream()
                        .filter(entry -> entry.getSourcePageId() != null
                                && entry.getSourcePageId().equals(pageId))
                        .findFirst())
                .or(() -> store.listFastEntries(SCENE, FastEntryStatus.OFFLINE).stream()
                        .filter(entry -> entry.getSourcePageId() != null
                                && entry.getSourcePageId().equals(pageId))
                        .findFirst())
                .orElseThrow(() -> new AssertionError("没有找到标题 " + title + " 派生出的条目"));
    }

    private JsonNode get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).GET().build();
        return send(request);
    }

    private JsonNode post(String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(2));
        builder.POST(body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return send(builder.build());
    }

    private JsonNode send(HttpRequest request) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("响应体：%s", response.body()).isEqualTo(200);
        JsonNode envelope = objectMapper.readTree(response.body());
        assertThat(envelope.get("code").asInt()).as("响应体：%s", response.body()).isEqualTo(200);
        return envelope.get("data");
    }
}
