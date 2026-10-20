package com.ragwiki.eval.runner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 汇总口径：分通道统计延迟与 token，召回只在带期望来源的用例上算，幻觉率按可判定的文本事实算。
 */
class AggregatorTest {

    private static EvalRunner.Result result(String caseId, String caseType, boolean passed,
                                            String channel, int latencyMillis) {
        EvalRunner.Result result = new EvalRunner.Result();
        result.caseId = caseId;
        result.caseType = caseType;
        result.question = caseId;
        result.passed = passed;
        result.channel = channel;
        result.latencyMillis = latencyMillis;
        result.citationOk = true;
        result.actualRefused = false;
        return result;
    }

    private static Object metric(Map<String, Object> metrics, String key) {
        return metrics.get(key);
    }

    private static int intMetric(Map<String, Object> metrics, String key) {
        return ((Number) metric(metrics, key)).intValue();
    }

    private static double doubleMetric(Map<String, Object> metrics, String key) {
        return ((Number) metric(metrics, key)).doubleValue();
    }

    @Test
    void countsPassedAndFailedCases() {
        Aggregator.Aggregate aggregate = Aggregator.aggregate(List.of(
                result("policy-1", "POLICY", true, "RAG", 100),
                result("policy-2", "POLICY", false, "RAG", 200)));

        assertThat(aggregate.passed()).isEqualTo(1);
        assertThat(aggregate.failed()).isEqualTo(1);
        assertThat(doubleMetric(aggregate.metrics(), "passRate")).isEqualTo(0.5);
        assertThat(intMetric(aggregate.metrics(), "caseCount")).isEqualTo(2);
    }

    @Test
    void splitsLatencyAndTokensByChannel() {
        EvalRunner.Result fast = result("fast-1", "POLICY", true, "FASTPATH", 5);
        fast.tokenIn = 0;
        fast.tokenOut = 0;
        EvalRunner.Result slow = result("policy-1", "POLICY", true, "RAG", 900);
        slow.tokenIn = 1200;
        slow.tokenOut = 300;

        Map<String, Object> metrics = Aggregator.aggregate(List.of(fast, slow)).metrics();

        assertThat(intMetric(metrics, "fastPathCount")).isEqualTo(1);
        assertThat(doubleMetric(metrics, "fastPathHitRate")).isEqualTo(0.5);
        assertThat(intMetric(metrics, "fastPathLatencyP99")).isEqualTo(5);
        assertThat(intMetric(metrics, "ragCount")).isEqualTo(1);
        assertThat(intMetric(metrics, "ragTokenInTotal")).isEqualTo(1200);
        assertThat(intMetric(metrics, "ragTokenOutTotal")).isEqualTo(300);
        // 分通道算，不把毫秒级与秒级混成一个平均数
        assertThat(intMetric(metrics, "ragLatencyP50")).isEqualTo(900);
    }

    @Test
    void skipsResultsWithoutLatencyWhenComputingPercentiles() {
        EvalRunner.Result skipped = result("policy-1", "POLICY", true, "RAG", 0);
        EvalRunner.Result measured = result("policy-2", "POLICY", true, "RAG", 400);

        Map<String, Object> metrics = Aggregator.aggregate(List.of(skipped, measured)).metrics();

        assertThat(intMetric(metrics, "ragCount")).isEqualTo(1);
        assertThat(intMetric(metrics, "ragLatencyP99")).isEqualTo(400);
    }

    @Test
    void recallAndMrrOnlyCountCasesWithExpectedSource() {
        EvalRunner.Result hitAtRank1 = result("policy-1", "POLICY", true, "RAG", 100);
        hitAtRank1.hasExpectedSource = true;
        hitAtRank1.expectedBestRank = 1;
        EvalRunner.Result hitAtRank3 = result("policy-2", "POLICY", false, "RAG", 100);
        hitAtRank3.hasExpectedSource = true;
        hitAtRank3.expectedBestRank = 3;
        EvalRunner.Result missed = result("policy-3", "POLICY", false, "RAG", 100);
        missed.hasExpectedSource = true;
        missed.expectedBestRank = 0;
        // 订单类用例没有期望来源，不进召回分母
        EvalRunner.Result order = result("order-1", "ORDER", true, "RAG", 100);

        Map<String, Object> metrics = Aggregator.aggregate(
                List.of(hitAtRank1, hitAtRank3, missed, order)).metrics();

        assertThat(intMetric(metrics, "retrievalCaseCount")).isEqualTo(3);
        assertThat(doubleMetric(metrics, "recallRate")).isEqualTo(0.6667);
        assertThat(doubleMetric(metrics, "top1Rate")).isEqualTo(0.3333);
        // MRR = (1/1 + 1/3 + 0) / 3
        assertThat(doubleMetric(metrics, "mrr")).isEqualTo(0.4444);
    }

    @Test
    void refusalCorrectRateUsesRefusalCasesOnly() {
        Aggregator.Aggregate aggregate = Aggregator.aggregate(List.of(
                result("refusal-1", "REFUSAL", true, "RAG", 100),
                result("refusal-2", "REFUSAL", false, "RAG", 100),
                result("policy-1", "POLICY", true, "RAG", 100)));

        assertThat(intMetric(aggregate.metrics(), "refusalCaseCount")).isEqualTo(2);
        assertThat(doubleMetric(aggregate.metrics(), "refusalCorrectRate")).isEqualTo(0.5);
    }

    @Test
    void hallucinationCountsForbiddenCitationAndOutOfScopeAnswers() {
        EvalRunner.Result forbidden = result("adversarial-1", "ADVERSARIAL", false, "RAG", 100);
        forbidden.forbiddenHits = List.of("30 元");
        EvalRunner.Result badCitation = result("policy-1", "POLICY", false, "RAG", 100);
        badCitation.citationOk = false;
        EvalRunner.Result outOfScope = result("refusal-1", "REFUSAL", false, "RAG", 100);
        outOfScope.actualRefused = false;
        EvalRunner.Result clean = result("policy-2", "POLICY", true, "RAG", 100);

        Map<String, Object> metrics = Aggregator.aggregate(
                List.of(forbidden, badCitation, outOfScope, clean)).metrics();

        assertThat(intMetric(metrics, "forbiddenViolationCount")).isEqualTo(1);
        assertThat(intMetric(metrics, "citationViolationCount")).isEqualTo(1);
        assertThat(intMetric(metrics, "hallucinationCount")).isEqualTo(3);
        assertThat(doubleMetric(metrics, "hallucinationRate")).isEqualTo(0.75);
    }

    @Test
    void answerPointRateAggregatesEveryPointNotEveryCase() {
        EvalRunner.Result twoOfThree = result("policy-1", "POLICY", false, "RAG", 100);
        twoOfThree.points = new AnswerJudge.PointOutcome(3, 2, List.of(), List.of());
        EvalRunner.Result oneOfOne = result("policy-2", "POLICY", true, "RAG", 100);
        oneOfOne.points = new AnswerJudge.PointOutcome(1, 1, List.of(), List.of());

        Map<String, Object> metrics = Aggregator.aggregate(List.of(twoOfThree, oneOfOne)).metrics();

        assertThat(intMetric(metrics, "answerPointsTotal")).isEqualTo(4);
        assertThat(intMetric(metrics, "answerPointsHit")).isEqualTo(3);
        assertThat(doubleMetric(metrics, "answerPointRate")).isEqualTo(0.75);
    }

    @Test
    void attributionCountsEachFailedLayer() {
        EvalRunner.Result recall = result("policy-1", "POLICY", false, "RAG", 100);
        recall.layer = Attributor.Layer.RECALL;
        EvalRunner.Result generation = result("policy-2", "POLICY", false, "RAG", 100);
        generation.layer = Attributor.Layer.GENERATION;
        EvalRunner.Result passed = result("policy-3", "POLICY", true, "RAG", 100);
        passed.layer = Attributor.Layer.NONE;

        Aggregator.Aggregate aggregate = Aggregator.aggregate(List.of(recall, generation, passed));

        assertThat(aggregate.attribution())
                .containsEntry("RECALL", 1)
                .containsEntry("GENERATION", 1)
                .containsEntry("COMPILE", 0)
                .containsEntry("FALLBACK", 0)
                .containsEntry("RERANK", 0)
                .doesNotContainKey("NONE");
    }

    @Test
    void emptyBatchProducesZeroesInsteadOfNaN() {
        Aggregator.Aggregate aggregate = Aggregator.aggregate(new ArrayList<>());

        assertThat(aggregate.passed()).isZero();
        assertThat(aggregate.failed()).isZero();
        assertThat(doubleMetric(aggregate.metrics(), "passRate")).isEqualTo(0.0);
        assertThat(doubleMetric(aggregate.metrics(), "hallucinationRate")).isEqualTo(0.0);
    }
}
