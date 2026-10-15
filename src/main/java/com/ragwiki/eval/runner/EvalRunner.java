package com.ragwiki.eval.runner;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragwiki.agent.AgentProperties;
import com.ragwiki.agent.api.AgentChatApi;
import com.ragwiki.agent.api.EvalFixtureApi;
import com.ragwiki.common.model.ModelProperties;
import com.ragwiki.eval.entity.EvalCase;
import com.ragwiki.eval.entity.EvalResult;
import com.ragwiki.eval.entity.EvalRun;
import com.ragwiki.eval.mapper.EvalCaseMapper;
import com.ragwiki.eval.mapper.EvalResultMapper;
import com.ragwiki.eval.mapper.EvalRunMapper;
import com.ragwiki.rag.RagProperties;
import com.ragwiki.rag.api.IndexControlApi;
import com.ragwiki.rag.api.KnowledgeProbeApi;
import com.ragwiki.wiki.api.WikiCatalogApi;
import com.ragwiki.wiki.fastpath.FastPathProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 批量执行评测用例，逐条判定与归因，汇总指标落库。
 *
 * <p>每条用例做两件事：
 * <ol>
 *   <li>跑一轮真实对话，拿答案、工具调用、token 与耗时</li>
 *   <li>把同一个问题打进检索探针，拿召回片段与两路原始分</li>
 * </ol>
 * 两者分开是为了让归因能分层：模型没调工具是生成层的事，
 * 期望片段没进候选窗口是召回层的事，混在一条链路上就分不开了。
 *
 * <p>会话 id 统一加 eval- 前缀，跑完能从 query_log 里把评测流量摘出来。
 */
@Service
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    /** 评测会话前缀，用来把评测流量从真实流量里摘出来 */
    public static final String SESSION_PREFIX = "eval-";

    private final AgentChatApi agentChatApi;
    private final KnowledgeProbeApi probeApi;
    private final WikiCatalogApi catalogApi;
    private final IndexControlApi indexControlApi;
    private final EvalFixtureApi fixtureApi;
    private final EvalCaseMapper caseMapper;
    private final EvalRunMapper runMapper;
    private final EvalResultMapper resultMapper;
    private final ObjectMapper objectMapper;
    private final AgentProperties agentProperties;
    private final RagProperties ragProperties;
    private final FastPathProperties fastPathProperties;
    private final ModelProperties modelProperties;

    public EvalRunner(AgentChatApi agentChatApi,
                      KnowledgeProbeApi probeApi,
                      WikiCatalogApi catalogApi,
                      IndexControlApi indexControlApi,
                      EvalFixtureApi fixtureApi,
                      EvalCaseMapper caseMapper,
                      EvalRunMapper runMapper,
                      EvalResultMapper resultMapper,
                      ObjectMapper objectMapper,
                      AgentProperties agentProperties,
                      RagProperties ragProperties,
                      FastPathProperties fastPathProperties,
                      ModelProperties modelProperties) {
        this.agentChatApi = agentChatApi;
        this.probeApi = probeApi;
        this.catalogApi = catalogApi;
        this.indexControlApi = indexControlApi;
        this.fixtureApi = fixtureApi;
        this.caseMapper = caseMapper;
        this.runMapper = runMapper;
        this.resultMapper = resultMapper;
        this.objectMapper = objectMapper;
        this.agentProperties = agentProperties;
        this.ragProperties = ragProperties;
        this.fastPathProperties = fastPathProperties;
        this.modelProperties = modelProperties;
    }

    /**
     * 跑一批。用例从 eval_case 表读，也就是从 eval/cases/*.jsonl 导入的快照。
     *
     * @param label     批次说明
     * @param caseTypes 跑哪几类用例，为空表示全部
     */
    public EvalRun run(String label, List<String> caseTypes) {
        List<EvalCase> cases = loadCases(caseTypes);
        if (cases.isEmpty()) {
            throw new IllegalStateException("没有匹配的用例，先调 POST /api/eval/cases/import 导入");
        }

        String runId = "run-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
        EvalRun run = new EvalRun();
        run.setRunId(runId);
        run.setLabel(label);
        run.setCaseTypes(caseTypes.isEmpty() ? "ALL" : String.join(",", caseTypes));
        run.setConfig(writeJson(configSnapshot()));
        run.setCaseCount(cases.size());
        run.setStatus(EvalRun.STATUS_RUNNING);
        Instant startedAt = Instant.now();
        run.setStartedAt(startedAt);
        run.setCreatedAt(startedAt);
        run.setPassed(0);
        run.setFailed(0);
        run.setLatencyMs(0L);
        run.setMetrics("{}");
        run.setAttribution("{}");
        runMapper.insert(run);

        log.info("评测批次 {} 开始：{} 条用例，配置 {} 索引", runId, cases.size(),
                indexControlApi.currentAliasTarget());

        return executeBatch(run, cases, new ArrayList<>(cases.size()));
    }

    /**
     * 接着一个没跑完的批次继续跑。
     *
     * <p>每条用例跑完就落库，中断时已经跑完的结果都在表里，所以续跑只要补
     * eval_result 里还没有的用例，汇总时把两段的结果合起来算。
     *
     * <p>已完成的批次不允许续跑：重跑会覆盖逐条结果，两段的配置也可能不同
     * （比如中途切了索引），混在一起算出来的指标没有意义。
     */
    public EvalRun resume(String runId) {
        EvalRun run = runMapper.selectOne(Wrappers.<EvalRun>lambdaQuery().eq(EvalRun::getRunId, runId));
        if (run == null) {
            throw new IllegalStateException("批次不存在：" + runId);
        }
        if (EvalRun.STATUS_COMPLETED.equals(run.getStatus())) {
            throw new IllegalStateException("批次已完成，不需要续跑：" + runId);
        }

        List<EvalCase> cases = loadCases(parseCaseTypes(run.getCaseTypes()));
        List<Result> restored = restore(runId);
        Set<String> done = new HashSet<>();
        for (Result result : restored) {
            done.add(result.caseId);
        }
        List<EvalCase> remaining = new ArrayList<>();
        for (EvalCase evalCase : cases) {
            if (!done.contains(evalCase.getCaseId())) {
                remaining.add(evalCase);
            }
        }

        // 用例集变了会出现在途批次对不上：按当前用例集重算总数，缺的补齐
        run.setCaseCount(cases.size());
        run.setStatus(EvalRun.STATUS_RUNNING);
        run.setFinishedAt(null);
        runMapper.updateById(run);

        log.info("评测批次 {} 续跑：已完成 {} 条，待跑 {} 条，当前 {} 索引",
                runId, done.size(), remaining.size(), indexControlApi.currentAliasTarget());

        return executeBatch(run, remaining, restored);
    }

    /** 逐条执行并落库，最后汇总。中断时已跑完的结果不丢 */
    private EvalRun executeBatch(EvalRun run, List<EvalCase> cases, List<Result> results) {
        String runId = run.getRunId();
        long baseMillis = run.getLatencyMs() == null ? 0L : run.getLatencyMs();
        long startedNanos = System.nanoTime();
        restoreFixtures();

        try {
            for (EvalCase evalCase : cases) {
                Result result = execute(runId, evalCase);
                // 先落库再进内存汇总：中途挂掉时表里就是已跑完的那部分
                persist(runId, result);
                results.add(result);
            }
        } catch (Exception e) {
            markInterrupted(run, results, baseMillis + elapsedMillis(startedNanos));
            log.warn("评测批次 {} 中断，已完成 {} 条：{}", runId, results.size(), e.getMessage());
            throw e;
        }

        long latencyMillis = baseMillis + elapsedMillis(startedNanos);
        Aggregator.Aggregate aggregate = Aggregator.aggregate(results);
        run.setPassed(aggregate.passed());
        run.setFailed(aggregate.failed());
        run.setMetrics(writeJson(aggregate.metrics()));
        run.setAttribution(writeJson(aggregate.attribution()));
        run.setLatencyMs(latencyMillis);
        run.setStatus(EvalRun.STATUS_COMPLETED);
        run.setFinishedAt(Instant.now());
        runMapper.updateById(run);

        log.info("评测批次 {} 完成：通过 {} / 失败 {}，耗时 {}ms",
                runId, aggregate.passed(), aggregate.failed(), latencyMillis);
        return run;
    }

    /** 中断收尾：状态标 INTERRUPTED，已跑完的条数与耗时记下来，汇总指标不写（这批没跑完） */
    private void markInterrupted(EvalRun run, List<Result> results, long latencyMillis) {
        int passed = 0;
        for (Result result : results) {
            if (result.passed) {
                passed++;
            }
        }
        run.setPassed(passed);
        run.setFailed(results.size() - passed);
        run.setLatencyMs(latencyMillis);
        run.setStatus(EvalRun.STATUS_INTERRUPTED);
        run.setFinishedAt(Instant.now());
        runMapper.updateById(run);
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /** 把已落库的逐条结果还原成内存对象，供续跑时合并汇总 */
    private List<Result> restore(String runId) {
        List<EvalResult> rows = resultMapper.selectList(Wrappers.<EvalResult>lambdaQuery()
                .eq(EvalResult::getRunId, runId).orderByAsc(EvalResult::getCaseId));
        List<Result> restored = new ArrayList<>(rows.size());
        for (EvalResult row : rows) {
            restored.add(restore(row));
        }
        return restored;
    }

    private Result restore(EvalResult row) {
        Map<String, Object> detail = readMap(row.getDetail());
        Result result = new Result();
        result.caseId = row.getCaseId();
        result.caseType = asString(detail.get("caseType"));
        result.question = asString(detail.get("question"));
        result.channel = row.getChannel();
        result.latencyMillis = row.getLatencyMs() == null ? 0 : row.getLatencyMs();
        result.tokenIn = row.getTokenIn();
        result.tokenOut = row.getTokenOut();
        result.passed = Boolean.TRUE.equals(row.getPassed());
        result.layer = row.getAttributionLayer() == null
                ? null : Attributor.Layer.valueOf(row.getAttributionLayer());
        // 汇总只用到要点总数与命中数，逐点明细不必还原
        result.points = new AnswerJudge.PointOutcome(
                asInt(detail.get("pointsTotal")), asInt(detail.get("pointsHit")), List.of(), List.of());
        result.forbiddenHits = asStringList(detail.get("forbiddenHits"));
        result.actualRefused = asBoolean(detail.get("actualRefused"));
        result.toolHit = asBoolean(detail.get("toolHit"));
        result.toolArgMisses = asStringList(detail.get("toolArgMisses"));
        result.citationOk = asBoolean(detail.get("citationOk"));
        result.sourceMissing = asBoolean(detail.get("sourceMissing"));
        result.hasExpectedSource = asBoolean(detail.get("hasExpectedSource"));
        result.expectedBestRank = asInt(detail.get("expectedBestRank"));
        result.error = row.getError();
        return result;
    }

    private List<String> parseCaseTypes(String caseTypes) {
        if (caseTypes == null || caseTypes.isBlank() || "ALL".equals(caseTypes)) {
            return List.of();
        }
        return List.of(caseTypes.split(","));
    }

    /**
     * 一条用例的执行与判定。
     *
     * <p>会话 id 里带上 runId：用例 id 是固定的，只用 caseId 做会话 id 的话，
     * 重跑同一批会接上上一批的对话历史，同一道题两次跑出不同结果，批次之间就没法比。
     */
    private Result execute(String runId, EvalCase evalCase) {
        List<List<String>> points = readPoints(evalCase.getExpectedAnswerPoints());
        List<String> titles = readStringList(evalCase.getExpectedSources());
        List<String> docIds = readStringList(evalCase.getExpectedDocIds());
        List<String> forbidden = readStringList(evalCase.getForbiddenPoints());
        List<String> expectTools = readStringList(evalCase.getExpectTools());
        Map<String, Object> expectedToolArgs = readMap(evalCase.getExpectedToolArgs());
        boolean expectRefusal = Boolean.TRUE.equals(evalCase.getExpectRefusal());

        Result result = new Result();
        result.caseId = evalCase.getCaseId();
        result.caseType = evalCase.getCaseType();
        result.question = evalCase.getQuestion();

        AgentChatApi.TurnResult turn;
        long startedNanos = System.nanoTime();
        try {
            turn = agentChatApi.runTurn(sessionId(runId, evalCase.getCaseId()), evalCase.getQuestion());
        } catch (Exception e) {
            result.error = e.getClass().getSimpleName() + "：" + e.getMessage();
            result.latencyMillis = (int) ((System.nanoTime() - startedNanos) / 1_000_000);
            result.passed = false;
            result.layer = Attributor.Layer.GENERATION;
            return result;
        }
        result.latencyMillis = (int) ((System.nanoTime() - startedNanos) / 1_000_000);
        result.answer = turn.answer();
        result.tokenIn = turn.tokenIn();
        result.tokenOut = turn.tokenOut();
        result.channel = turn.fastPathHit() ? "FASTPATH" : "RAG";
        result.finishReason = turn.finishReason();
        result.error = turn.error();

        // 拒答用例与订单用例不进检索：前者知识库里本就没有，后者没有期望来源
        KnowledgeProbeApi.ProbeResult probe = null;
        if (!expectRefusal && (!titles.isEmpty() || !docIds.isEmpty())) {
            try {
                probe = probeApi.probe(evalCase.getQuestion());
            } catch (Exception e) {
                log.warn("检索探针失败 caseId={}：{}", evalCase.getCaseId(), e.getMessage());
            }
        }
        result.probe = probe;

        // 答案判定
        AnswerJudge.PointOutcome pointOutcome = AnswerJudge.judgePoints(turn.answer(), points);
        List<String> forbiddenHits = AnswerJudge.judgeForbidden(turn.answer(), forbidden);
        // 要点全中时不判拒答：措辞分不出「整体拒答」与「答完正题再如实补一句缺口」
        boolean refused = AnswerJudge.judgedRefusal(
                turn.answer(), pointOutcome.allHit(), expectRefusal);
        // 快通道命中时不判工具：它按设计就不检索、不调工具，拿 expect_tools 判它失败是口径错。
        // 但快通道截胡了需要查数据的问题，答案要点自然对不上，那类问题由要点判定兜住
        boolean toolRequired = !expectTools.isEmpty() && !turn.fastPathHit();
        boolean toolHit = !toolRequired || turn.toolInvocations().stream()
                .anyMatch(invocation -> expectTools.contains(invocation.name()));
        // 工具名对了不代表参数对：订单号传错会查到别人的单，只看名字会把这种错判成通过
        List<String> toolArgMisses = toolRequired
                ? judgeToolArgs(turn, expectTools, expectedToolArgs) : List.of();
        boolean citationOk = true;
        int citationCount = 0;
        if (probe != null && !probe.chunks().isEmpty()) {
            citationCount = Math.min(probe.topK(), probe.chunks().size());
        }
        if (citationCount > 0 && !turn.fastPathHit()) {
            citationOk = AnswerJudge.citationsInRange(turn.answer(), citationCount);
        }

        result.points = pointOutcome;
        result.forbiddenHits = forbiddenHits;
        result.actualRefused = refused;
        result.toolHit = toolHit;
        result.toolArgMisses = toolArgMisses;
        result.citationOk = citationOk;
        // 期望来源的最优名次在这里算好，汇总时直接用，不再回查片段
        if (probe != null && !expectRefusal && (!titles.isEmpty() || !docIds.isEmpty())) {
            result.expectedBestRank = probe.bestRank(titles, docIds);
            result.hasExpectedSource = true;
        }

        // 期望来源在库里是否存在：不存在是编译层的问题，与「存在但没召回」分开
        Set<String> knownTitles = catalogApi.activePageTitles();
        Set<String> knownDocs = catalogApi.sourceDocIds();
        boolean sourceMissing = false;
        if (!expectRefusal) {
            for (String title : titles) {
                if (!knownTitles.contains(title)) {
                    sourceMissing = true;
                    result.missingSources.add("条目：" + title);
                }
            }
            for (String docId : docIds) {
                if (!knownDocs.contains(docId)) {
                    sourceMissing = true;
                    result.missingSources.add("文档：" + docId);
                }
            }
        }
        result.sourceMissing = sourceMissing;

        boolean answerPassed = points.isEmpty() ? true : pointOutcome.allHit();
        result.passed = judgePassed(expectRefusal, refused, answerPassed, forbiddenHits,
                toolHit, citationOk, toolArgMisses, turn);

        boolean toolOk = toolHit && toolArgMisses.isEmpty();
        Attributor.Input input = new Attributor.Input(expectRefusal, probe, titles, docIds,
                answerPassed, refused, sourceMissing, toolOk, !forbiddenHits.isEmpty());
        result.layer = result.passed ? Attributor.Layer.NONE : Attributor.attribute(input);
        // 通过了但答案里有越界引用，仍要指出是生成层的问题
        if (result.passed && !citationOk) {
            result.passed = false;
            result.layer = Attributor.Layer.GENERATION;
        }
        if (result.passed && !toolOk) {
            result.passed = false;
            result.layer = Attributor.Layer.GENERATION;
        }
        return result;
    }

    /**
     * 工具参数判定：期望的每个参数键值都要在某次同名调用里出现。
     *
     * <p>参数值按归一化后的子串匹配，不做全等：模型常把订单号写成「订单 SO20250901001」
     * 或带上多余空白，这类写法不算错。判的是订单号本身有没有传对。
     */
    private List<String> judgeToolArgs(AgentChatApi.TurnResult turn,
                                       List<String> expectTools, Map<String, Object> expected) {
        if (expected.isEmpty()) {
            return List.of();
        }
        List<String> misses = new ArrayList<>();
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            String key = entry.getKey();
            String want = AnswerJudge.normalize(String.valueOf(entry.getValue()));
            boolean matched = turn.toolInvocations().stream()
                    .filter(invocation -> expectTools.isEmpty() || expectTools.contains(invocation.name()))
                    .anyMatch(invocation -> AnswerJudge.normalize(invocation.arguments()).contains(want));
            if (!matched) {
                misses.add(key + "=" + entry.getValue());
            }
        }
        return List.copyOf(misses);
    }

    private boolean judgePassed(boolean expectRefusal, boolean refused, boolean answerPassed,
                                List<String> forbiddenHits, boolean toolHit, boolean citationOk,
                                List<String> toolArgMisses, AgentChatApi.TurnResult turn) {
        if (turn.error() != null) {
            return false;
        }
        if (!forbiddenHits.isEmpty()) {
            return false;
        }
        if (expectRefusal) {
            return refused;
        }
        // 该答的拒了，判失败；该答的答了，再看要点、工具与参数
        if (refused) {
            return false;
        }
        return answerPassed && toolHit && citationOk && toolArgMisses.isEmpty();
    }

    /** 评测会话 id：前缀 + 批次 + 用例，长度控制在 session_id 列宽 64 以内 */
    private static String sessionId(String runId, String caseId) {
        return SESSION_PREFIX + runId + "-" + caseId;
    }

    /**
     * 跑批前把会被工具改写的夹具恢复成基线。
     *
     * <p>订单状态是可变的：上一批某个用例调用 refund 把订单改成了 REFUNDING，
     * 这一批再问同一订单拿到的状态就和用例标注对不上了。不复位的话，
     * 批次之间的差异里混着「夹具漂移」这一项，越往后跑越差，与模型无关。
     */
    private void restoreFixtures() {
        try {
            int restored = fixtureApi.restoreOrderFixtures();
            if (restored > 0) {
                log.info("评测夹具复位：{} 个订单回到基线状态", restored);
            }
        } catch (Exception e) {
            log.warn("评测夹具复位失败，本批次的订单用例可能与标注不符：{}", e.getMessage());
        }
    }

    private List<EvalCase> loadCases(List<String> caseTypes) {
        var query = Wrappers.<EvalCase>lambdaQuery();
        if (!caseTypes.isEmpty()) {
            query.in(EvalCase::getCaseType, caseTypes);
        }
        query.orderByAsc(EvalCase::getCaseId);
        return caseMapper.selectList(query);
    }

    private void persist(String runId, Result result) {
        EvalResult entity = new EvalResult();
        entity.setRunId(runId);
        entity.setCaseId(result.caseId);
        entity.setPassed(result.passed);
        entity.setChannel(result.channel == null ? "RAG" : result.channel);
        entity.setAttributionLayer(result.layer == null ? null : result.layer.name());
        entity.setDetail(writeJson(result.detail()));
        entity.setAnswer(abbreviate(result.answer));
        entity.setLatencyMs(result.latencyMillis);
        entity.setTokenIn(result.tokenIn);
        entity.setTokenOut(result.tokenOut);
        entity.setError(abbreviate(result.error));
        entity.setCreatedAt(Instant.now());
        resultMapper.insert(entity);
    }

    /** 批次配置快照。换了模型或改了切分参数，靠它区分批次 */
    private Map<String, Object> configSnapshot() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("alias", ragProperties.index().alias());
        config.put("aliasTarget", indexControlApi.currentAliasTarget());
        config.put("indexSource", ragProperties.index().source());
        config.put("indexVersion", ragProperties.index().version());
        config.put("chunkMaxChars", ragProperties.chunk().maxChars());
        config.put("chunkOverlapChars", ragProperties.chunk().overlapChars());
        config.put("candidateWindow", ragProperties.candidateWindow());
        config.put("topK", ragProperties.topK());
        config.put("rrfK", ragProperties.rrf().k());
        config.put("refuseBm25Threshold", ragProperties.refuse().bm25Threshold());
        config.put("refuseVectorThreshold", ragProperties.refuse().vectorThreshold());
        config.put("fastPathEnabled", agentProperties.fastPathEnabled());
        config.put("fastPathMinConfidence", fastPathProperties.minConfidence());
        config.put("agentMaxSteps", agentProperties.maxSteps());
        config.put("chatModel", modelProperties.chat().model());
        config.put("embeddingModel", modelProperties.embedding().model());
        config.put("embeddingDimensions", modelProperties.embedding().dimensions());
        return config;
    }

    // ---------- JSON 读写 ----------

    private List<List<String>> readPoints(String json) {
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("用例要点字段无法解析：" + json, e);
        }
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("用例字段无法解析：" + json, e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("判定明细无法解析", e);
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static boolean asBoolean(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 2000 ? text : text.substring(0, 2000);
    }

    /** 一条用例的执行结果，判定与归因都在这里 */
    public static final class Result {

        String caseId;
        String caseType;
        String question;
        String answer;
        String channel;
        String finishReason;
        String error;
        int latencyMillis;
        Integer tokenIn;
        Integer tokenOut;
        boolean passed;
        Attributor.Layer layer;
        AnswerJudge.PointOutcome points;
        List<String> forbiddenHits = List.of();
        boolean actualRefused;
        boolean toolHit;
        /** 期望工具参数里没传对的项，形如 orderNo=SO20250901001 */
        List<String> toolArgMisses = List.of();
        boolean citationOk;
        boolean sourceMissing;
        /** 这条用例是否带期望来源（订单类不带） */
        boolean hasExpectedSource;
        /** 期望来源在融合结果里的最优名次，0 表示没召回 */
        int expectedBestRank;
        final List<String> missingSources = new ArrayList<>();
        KnowledgeProbeApi.ProbeResult probe;

        /** 落库的判定明细。失败复核时看的就是这一块 */
        Map<String, Object> detail() {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("caseType", caseType);
            detail.put("question", question);
            detail.put("channel", channel);
            detail.put("finishReason", finishReason);
            detail.put("pointsTotal", points == null ? 0 : points.total());
            detail.put("pointsHit", points == null ? 0 : points.hit());
            detail.put("pointsMissed", points == null ? List.of() : points.missed());
            detail.put("forbiddenHits", forbiddenHits);
            detail.put("actualRefused", actualRefused);
            detail.put("toolHit", toolHit);
            detail.put("toolArgMisses", toolArgMisses);
            detail.put("citationOk", citationOk);
            detail.put("sourceMissing", sourceMissing);
            detail.put("missingSources", missingSources);
            // 这两项续跑还原时要读，必须落库
            detail.put("hasExpectedSource", hasExpectedSource);
            detail.put("expectedBestRank", expectedBestRank);
            if (probe != null) {
                detail.put("probeRefused", probe.refused());
                detail.put("probeTopBm25", probe.topBm25Score());
                detail.put("probeTopVector", probe.topVectorScore());
                detail.put("probeChunks", probe.chunks().stream().limit(probe.topK())
                        .map(chunk -> Map.of(
                                "chunkId", chunk.chunkId(),
                                "title", chunk.title(),
                                "docId", chunk.docId()))
                        .toList());
            }
            return detail;
        }
    }
}
