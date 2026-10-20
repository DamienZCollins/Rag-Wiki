package com.ragwiki.eval;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragwiki.eval.entity.EvalCase;
import com.ragwiki.eval.entity.EvalResult;
import com.ragwiki.eval.entity.EvalRun;
import com.ragwiki.eval.mapper.EvalCaseMapper;
import com.ragwiki.eval.mapper.EvalResultMapper;
import com.ragwiki.eval.mapper.EvalRunMapper;
import com.ragwiki.support.StubModelServer;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测批次的最小闭环：导入两条临时用例 → 跑批 → 报告 → 门禁。
 *
 * <p>只跑两条：完整 122 条要十几分钟，不适合放进 mvn test。
 * 这里验的是批次的接线与口径（用例读库、逐条落库、汇总、归因、门禁读基线），
 * 真实指标必须用真实模型跑完整批次。
 *
 * <p>用例目录、基线文件、订单夹具都指向临时目录或临时会话，不动仓库里的评测数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EvalSmokeIntegrationTest {

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
        registry.add("ragwiki.eval.case-dir", () -> workDir.resolve("cases").toString());
        registry.add("ragwiki.eval.baseline", () -> workDir.resolve("baseline.json").toString());
        // 关闭快通道：两条用例都走模型桩，判定路径才是完整的一条
        registry.add("agent.fast-path-enabled", () -> false);
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
    private EvalCaseMapper caseMapper;

    @Autowired
    private EvalRunMapper runMapper;

    @Autowired
    private EvalResultMapper resultMapper;

    private String caseId;
    private String runId;

    @BeforeEach
    void setUp() {
        caseId = "it-smoke-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        // 直接写一条用例：导入接口是整表替换快照，会清掉本机真实的 122 条，不能在这里调
        EvalCase evalCase = new EvalCase();
        evalCase.setCaseId(caseId);
        evalCase.setCaseType(EvalCase.TYPE_ORDER);
        evalCase.setQuestion("帮我查一下订单 SO20250901001 现在什么状态？");
        evalCase.setExpectedAnswerPoints("[[\"已签收\"]]");
        evalCase.setExpectedSources("[]");
        evalCase.setExpectedDocIds("[]");
        evalCase.setExpectRefusal(false);
        evalCase.setExpectTools("[\"query_order\"]");
        evalCase.setExpectedToolArgs("{\"orderNo\":\"SO20250901001\"}");
        evalCase.setForbiddenPoints("[]");
        evalCase.setNote("smoke");
        evalCase.setSourceFile("smoke.jsonl");
        evalCase.setCreatedAt(java.time.Instant.now());
        caseMapper.insert(evalCase);
    }

    @AfterEach
    void cleanUp() {
        // 只清本用例写入的这一条用例与本次批次，不碰本机已有的评测数据
        caseMapper.delete(Wrappers.<EvalCase>lambdaQuery().eq(EvalCase::getCaseId, caseId));
        if (runId != null) {
            resultMapper.delete(Wrappers.<EvalResult>lambdaQuery().eq(EvalResult::getRunId, runId));
            runMapper.delete(Wrappers.<EvalRun>lambdaQuery().eq(EvalRun::getRunId, runId));
        }
    }

    @Test
    void runReportAndGateStayWiredEndToEnd() throws Exception {
        // 用例类型取自刚插入的那一条；库里其余 ORDER 用例也会进这一批，所以只断言总数一致
        JsonNode run = post("/api/eval/run",
                "{\"label\":\"smoke\",\"caseTypes\":[\"ORDER\"]}");
        runId = run.get("runId").asText();

        assertThat(run.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.get("caseCount").asInt()).isPositive();
        assertThat(run.get("passed").asInt() + run.get("failed").asInt())
                .isEqualTo(run.get("caseCount").asInt());

        // 逐条结果已落库，且刚插入的这条走的是慢通道
        List<EvalResult> results = resultMapper.selectList(Wrappers.<EvalResult>lambdaQuery()
                .eq(EvalResult::getRunId, runId));
        assertThat(results).extracting(EvalResult::getCaseId).contains(caseId);
        assertThat(results.stream().filter(result -> result.getCaseId().equals(caseId)).findFirst()
                .orElseThrow().getChannel()).isEqualTo("RAG");

        JsonNode report = get("/api/eval/report/" + runId);
        assertThat(report.get("metrics").get("caseCount").asInt())
                .isEqualTo(run.get("caseCount").asInt());
        // 归因只统计失败层，五个层键始终存在（可能都是 0）
        assertThat(report.get("attribution").has("GENERATION")).isTrue();

        // 基线写入临时文件，门禁拿同一批比自己应当通过
        JsonNode baseline = post("/api/eval/baseline/" + runId, null);
        assertThat(baseline.get("metrics").has("passRate")).isTrue();

        JsonNode gate = get("/api/eval/gate/" + runId);
        assertThat(gate.get("passed").asBoolean()).isTrue();
        assertThat(gate.get("baselineRunId").asText()).isEqualTo(runId);
    }

    // ---------- 辅助 ----------

    private JsonNode get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofMinutes(5)).GET().build();
        return send(request);
    }

    private JsonNode post(String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(10));
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
