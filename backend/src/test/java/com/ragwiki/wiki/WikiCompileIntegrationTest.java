package com.ragwiki.wiki;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragwiki.support.StubModelServer;
import com.ragwiki.wiki.mapper.FastEntryMapper;
import com.ragwiki.wiki.mapper.SourceDocMapper;
import com.ragwiki.wiki.mapper.WikiLinkMapper;
import com.ragwiki.wiki.mapper.WikiPageMapper;
import com.ragwiki.wiki.schema.FastEntry;
import com.ragwiki.wiki.schema.SourceDoc;
import com.ragwiki.wiki.schema.WikiLink;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiki 编译全链路：真实 HTTP、真实 MySQL、真实文件落盘，模型用桩按源文档标题回放固定 JSON。
 *
 * <p>覆盖验收要求里的三条：重复摄入不产生重复条目、政策改版后旧条目转为失效、lint 能报出冲突。
 * 语料目录与条目目录都指向临时目录，不碰仓库里的真实产物；库里的临时数据在用例结束后清掉。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WikiCompileIntegrationTest {

    private static StubModelServer stub;

    @TempDir
    static Path workDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        stub = new StubModelServer();
        registry.add("model.compile.base-url", stub::baseUrl);
        registry.add("model.compile.api-key", () -> "stub-key");
        registry.add("model.compile.model", () -> "stub-model");
        registry.add("model.embedding.base-url", stub::baseUrl);
        registry.add("model.embedding.api-key", () -> "stub-key");
        registry.add("model.embedding.model", () -> "stub-embedding");
        registry.add("ragwiki.corpus.policy-dir", () -> workDir.resolve("policies").toString());
        registry.add("wiki.page-dir", () -> workDir.resolve("pages").toString());
        // 桩向量没有语义，相似度筛不出候选对；调低阈值让两两比较都进模型，验的是「模型说有冲突就报出来」
        registry.add("wiki.lint-similarity-threshold", () -> 0.5);
        // 库里有真实语料编译出的条目，候选对要放宽上限，保证临时用例的同构条目对能进模型
        registry.add("wiki.lint-max-pairs", () -> 300);
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

    @Autowired
    private WikiStore store;

    @Autowired
    private GitPageStore pageStore;

    @Autowired
    private SourceDocMapper sourceDocMapper;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiLinkMapper wikiLinkMapper;

    @Autowired
    private FastEntryMapper fastEntryMapper;

    private Path policyDir;

    /** 每次用例用独立后缀，避免与库里已有条目标题撞车 */
    private String suffix;

    @BeforeEach
    void setUp() throws IOException {
        suffix = "-" + UUID.randomUUID().toString().substring(0, 8);
        policyDir = workDir.resolve("policies");
        Files.createDirectories(policyDir);
    }

    @AfterEach
    void cleanUp() throws IOException {
        deleteWikiRows();
        if (Files.isDirectory(policyDir)) {
            try (var files = Files.list(policyDir)) {
                for (Path file : files.toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    @Test
    void repeatedCompileUpdatesInPlaceInsteadOfDuplicating() throws Exception {
        String docId = "it-repeat" + suffix;
        String title = "重复摄入验证" + suffix;
        writePolicy(docId, "重复摄入验证" + suffix, "v2025.1", null, "# 正文\n\n单笔最高 20 元。");
        stub.script("重复摄入验证" + suffix, entriesJson(new String[][]{{title, "单笔最高 20 元。"}}));

        JsonNode first = post("/api/wiki/compile", "{\"incremental\":false}");
        assertThat(first.get("created").asInt()).isPositive();
        String pageId = WikiPage.pageIdOf(title);
        assertThat(store.findPage(pageId)).isPresent();

        JsonNode second = post("/api/wiki/compile", "{\"incremental\":false}");

        // 同一标题落的是同一个 page_id：第二次是更新，不是再建一条
        assertThat(second.get("created").asInt()).isZero();
        assertThat(second.get("updated").asInt()).isPositive();
        long sameTitle = store.listPages().stream()
                .filter(page -> title.equals(page.getTitle())).count();
        assertThat(sameTitle).isEqualTo(1);
        // 正文文件也只留一份
        assertThat(pageStore.read(pageId)).isPresent();
    }

    @Test
    void newVersionRetiresEntriesThatOnlyDependOnTheOldOne() throws Exception {
        String oldDoc = "it-old" + suffix;
        String newDoc = "it-new" + suffix;
        String oldTitle = "旧版口径条目" + suffix;
        String keptTitle = "新版重新确立条目" + suffix;
        String droppedTitle = "新版删掉的条目" + suffix;

        writePolicy(oldDoc, "运费政策旧版" + suffix, "v2024.1", null, "# 正文\n\n旧版口径。");
        stub.script("运费政策旧版" + suffix, entriesJson(
                new String[][]{{oldTitle, "旧版写 30 元。"}, {droppedTitle, "旧版有、新版删掉。"}},
                keptTitle));
        post("/api/wiki/compile", "{\"incremental\":false}");
        assertThat(store.findPage(WikiPage.pageIdOf(oldTitle)).orElseThrow().getStatus())
                .isEqualTo(WikiPageStatus.ACTIVE);

        // 新版取代旧版：旧文档转失效，重新确立的概念保持生效，没再出现的条目标为失效
        writePolicy(oldDoc, "运费政策旧版" + suffix, "v2024.1", null, "# 正文\n\n旧版口径。", "retired", newDoc);
        writePolicy(newDoc, "运费政策新版" + suffix, "v2025.1", oldDoc, "# 正文\n\n新版口径。");
        stub.script("运费政策新版" + suffix, entriesJson(
                new String[][]{{keptTitle, "新版写 20 元。"}}, new String[0]));

        JsonNode report = post("/api/wiki/compile", "{\"incremental\":false}");

        assertThat(report.get("retired").asInt()).isPositive();
        assertThat(report.get("retiredPages").toString())
                .contains(droppedTitle).contains(oldTitle)
                .doesNotContain(keptTitle);
        assertThat(store.findPage(WikiPage.pageIdOf(keptTitle)).orElseThrow().getStatus())
                .isEqualTo(WikiPageStatus.ACTIVE);
        assertThat(store.findPage(WikiPage.pageIdOf(droppedTitle)).orElseThrow().getStatus())
                .isEqualTo(WikiPageStatus.RETIRED);
    }

    @Test
    void lintReportsConflictBetweenSimilarEntries() throws Exception {
        String left = "冲突条目甲" + suffix;
        String right = "冲突条目乙" + suffix;
        writePolicy("it-conflict-a" + suffix, "冲突验证甲" + suffix, "v2025.1", null, "# 正文\n\n甲。");
        writePolicy("it-conflict-b" + suffix, "冲突验证乙" + suffix, "v2025.1", null, "# 正文\n\n乙。");
        // 两段摘要尽量同构，桩向量下相似度才高，才能排进候选对
        stub.script("冲突验证甲" + suffix, entriesJson(
                new String[][]{{left, "质量问题退货运费补贴上限，统一按 30 元执行。"}}, new String[0]));
        stub.script("冲突验证乙" + suffix, entriesJson(
                new String[][]{{right, "质量问题退货运费补贴上限，普通会员 20 元、金卡会员 30 元。"}}, new String[0]));

        post("/api/wiki/compile", "{\"incremental\":false}");
        // 冲突判定按批送，每批的编号都从 1 起；把每批前 5 对都报成冲突，等于全部选中对都命中
        StringBuilder verdict = new StringBuilder("{\"conflicts\":[");
        for (int pair = 1; pair <= 5; pair++) {
            verdict.append(pair > 1 ? "," : "")
                    .append("{\"pair\":").append(pair).append(",\"reason\":\"补贴口径不一致\"}");
        }
        stub.script("以下是知识库里几对相关条目的摘要与适用条件", verdict.append("]}").toString());

        JsonNode lint = post("/api/wiki/lint", null);

        JsonNode conflicts = lint.get("conflicts");
        assertThat(conflicts.size()).isPositive();
        boolean hit = false;
        for (JsonNode conflict : conflicts) {
            if (conflict.get("title").asText().equals(left)
                    || conflict.get("relatedTitle").asText().equals(left)) {
                hit = true;
                assertThat(conflict.get("detail").asText()).contains("补贴口径");
            }
        }
        assertThat(hit).as("冲突应定位到刚编译出来的条目").isTrue();
    }

    // ---------- 辅助 ----------

    private void writePolicy(String docId, String title, String version, String supersedes, String body)
            throws IOException {
        writePolicy(docId, title, version, supersedes, body, "active", null);
    }

    private void writePolicy(String docId, String title, String version, String supersedes, String body,
                             String status, String supersededBy) throws IOException {
        StringBuilder frontmatter = new StringBuilder("---\n");
        frontmatter.append("doc_id: ").append(docId).append('\n');
        frontmatter.append("title: ").append(title).append('\n');
        frontmatter.append("category: 测试\n");
        frontmatter.append("version: ").append(version).append('\n');
        frontmatter.append("effective_at: 2025-01-01\n");
        frontmatter.append("status: ").append(status).append('\n');
        if (supersedes != null) {
            frontmatter.append("supersedes: ").append(supersedes).append('\n');
        }
        if (supersededBy != null) {
            frontmatter.append("superseded_by: ").append(supersededBy).append('\n');
        }
        frontmatter.append("---\n\n").append(body).append('\n');
        Files.writeString(policyDir.resolve(docId + ".md"), frontmatter.toString(), StandardCharsets.UTF_8);
    }

    /** @param entries 每项是 {标题, 摘要} */
    private static String entriesJson(String[][] entries, String... related) {
        StringBuilder relatedJson = new StringBuilder("[");
        for (int i = 0; i < related.length; i++) {
            if (i > 0) {
                relatedJson.append(',');
            }
            relatedJson.append('"').append(related[i]).append('"');
        }
        relatedJson.append(']');

        StringBuilder json = new StringBuilder("{\"entries\":[");
        for (int i = 0; i < entries.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"title\":\"").append(entries[i][0])
                    .append("\",\"mergeInto\":\"\",\"summary\":\"").append(entries[i][1])
                    .append("\",\"conditions\":[\"测试条件\"],\"exceptions\":[],\"related\":")
                    .append(relatedJson).append('}');
        }
        return json.append("]}").toString();
    }

    private void deleteWikiRows() {
        // 只清本用例产生的数据：库里有真实语料与产品条目，不能整表清空
        sourceDocMapper.delete(Wrappers.<SourceDoc>lambdaQuery()
                .likeRight(SourceDoc::getDocId, "it-")
                .like(SourceDoc::getDocId, suffix));
        wikiPageMapper.delete(Wrappers.<WikiPage>lambdaQuery().like(WikiPage::getTitle, suffix));
        wikiLinkMapper.delete(Wrappers.<WikiLink>lambdaQuery()
                .like(WikiLink::getFromPageId, suffix).or().like(WikiLink::getToPageId, suffix));
        fastEntryMapper.delete(Wrappers.<FastEntry>lambdaQuery().like(FastEntry::getSourcePageId, suffix));
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
        assertThat(envelope.get("code").asInt()).as("响应体：%s", response.body()).isEqualTo(200);
        return envelope.get("data");
    }
}
