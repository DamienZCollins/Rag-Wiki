package com.ragwiki.eval.runner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 汇总指标。口径在这里落地，与 {@link Metrics} 的分位数与比率算法配套。
 *
 * <p>分通道统计延迟与 token：快通道与慢通道的量级差三个数量级，
 * 合在一起算平均会得出一个既不反映快也不反映慢的数。
 */
public final class Aggregator {

    private Aggregator() {
    }

    public record Aggregate(int passed, int failed, Map<String, Object> metrics,
                            Map<String, Object> attribution) {
    }

    public static Aggregate aggregate(List<EvalRunner.Result> results) {
        int passed = 0;
        int failed = 0;
        for (EvalRunner.Result result : results) {
            if (result.passed) {
                passed++;
            } else {
                failed++;
            }
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("caseCount", results.size());
        metrics.put("passed", passed);
        metrics.put("failed", failed);
        metrics.put("passRate", Metrics.round(Metrics.ratio(passed, results.size())));
        metrics.putAll(channelMetrics(results));
        metrics.putAll(retrievalMetrics(results));
        metrics.putAll(refusalMetrics(results));
        metrics.putAll(qualityMetrics(results));
        return new Aggregate(passed, failed, metrics, attributionOf(results));
    }

    /** 分通道：快通道命中率、P50/P95/P99 延迟、平均与总 token */
    private static Map<String, Object> channelMetrics(List<EvalRunner.Result> results) {
        Map<String, Object> metrics = new LinkedHashMap<>();

        List<Long> fastLatency = new ArrayList<>();
        List<Long> slowLatency = new ArrayList<>();
        List<Integer> slowTokenIn = new ArrayList<>();
        List<Integer> slowTokenOut = new ArrayList<>();
        long fastTokenIn = 0;
        long fastTokenOut = 0;
        int fastCount = 0;

        for (EvalRunner.Result result : results) {
            if (result.latencyMillis <= 0) {
                continue;
            }
            if ("FASTPATH".equals(result.channel)) {
                fastCount++;
                fastLatency.add((long) result.latencyMillis);
                fastTokenIn += result.tokenIn == null ? 0 : result.tokenIn;
                fastTokenOut += result.tokenOut == null ? 0 : result.tokenOut;
            } else {
                slowLatency.add((long) result.latencyMillis);
                slowTokenIn.add(result.tokenIn == null ? 0 : result.tokenIn);
                slowTokenOut.add(result.tokenOut == null ? 0 : result.tokenOut);
            }
        }

        metrics.put("fastPathCount", fastCount);
        metrics.put("fastPathHitRate", Metrics.round(Metrics.ratio(fastCount, results.size())));
        metrics.put("fastPathTokenIn", fastTokenIn);
        metrics.put("fastPathTokenOut", fastTokenOut);
        metrics.put("fastPathLatencyP50", Metrics.percentile(fastLatency, 50));
        metrics.put("fastPathLatencyP99", Metrics.percentile(fastLatency, 99));

        metrics.put("ragCount", slowLatency.size());
        metrics.put("ragTokenInTotal", Metrics.sum(slowTokenIn));
        metrics.put("ragTokenOutTotal", Metrics.sum(slowTokenOut));
        metrics.put("ragTokenInMean", Metrics.mean(slowTokenIn));
        metrics.put("ragTokenOutMean", Metrics.mean(slowTokenOut));
        metrics.put("ragLatencyP50", Metrics.percentile(slowLatency, 50));
        metrics.put("ragLatencyP95", Metrics.percentile(slowLatency, 95));
        metrics.put("ragLatencyP99", Metrics.percentile(slowLatency, 99));
        return metrics;
    }

    /**
     * 召回与排序：只在带期望来源的用例上算，分母是这类用例数。
     *
     * <p>Recall@K 用「进没进候选窗口」判，MRR 用期望来源的最优名次算。
     * 两者分开是因为一条用例可能「召回了但排在第 30 位」——
     * 召回率算它命中，但它对生成层没贡献，位次指标才反映得出这个差别。
     */
    private static Map<String, Object> retrievalMetrics(List<EvalRunner.Result> results) {
        int withSource = 0;
        int recalled = 0;
        int rank1 = 0;
        double reciprocalSum = 0;

        for (EvalRunner.Result result : results) {
            if (!result.hasExpectedSource) {
                continue;
            }
            withSource++;
            if (result.expectedBestRank > 0) {
                recalled++;
                reciprocalSum += 1.0 / result.expectedBestRank;
                if (result.expectedBestRank == 1) {
                    rank1++;
                }
            }
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("retrievalCaseCount", withSource);
        metrics.put("recallRate", Metrics.round(Metrics.ratio(recalled, withSource)));
        metrics.put("mrr", Metrics.round(withSource == 0 ? 0 : reciprocalSum / withSource));
        metrics.put("top1Rate", Metrics.round(Metrics.ratio(rank1, withSource)));
        return metrics;
    }

    /** 兜底：拒答正确率 */
    private static Map<String, Object> refusalMetrics(List<EvalRunner.Result> results) {
        int refusalCases = 0;
        int refusalCorrect = 0;
        for (EvalRunner.Result result : results) {
            if (!"REFUSAL".equals(result.caseType)) {
                continue;
            }
            refusalCases++;
            if (result.passed) {
                refusalCorrect++;
            }
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("refusalCaseCount", refusalCases);
        metrics.put("refusalCorrectRate", Metrics.round(Metrics.ratio(refusalCorrect, refusalCases)));
        return metrics;
    }

    /**
     * 质量：要点命中率、禁点违规、引用越界、幻觉率。
     *
     * <p>幻觉率的可执行口径——一条用例算「有幻觉」，当且仅当以下任一成立：
     * <ol>
     *   <li>引用越界：答案里出现 [n]，n 超出工具给出的片段数，说明引了不存在的内容</li>
     *   <li>禁点命中：答案给出了与该用例明确禁止的旧口径一致的结论</li>
     *   <li>拒答用例给出了确定性结论：知识库里本就没有依据，答了就是编的</li>
     * </ol>
     * 这三条都是可判定的文本事实，不靠主观打分。分母是全部用例数。
     */
    private static Map<String, Object> qualityMetrics(List<EvalRunner.Result> results) {
        int pointsTotal = 0;
        int pointsHit = 0;
        int forbiddenViolations = 0;
        int citationViolations = 0;
        int hallucinations = 0;

        for (EvalRunner.Result result : results) {
            if (result.points != null) {
                pointsTotal += result.points.total();
                pointsHit += result.points.hit();
            }
            boolean forbidden = !result.forbiddenHits.isEmpty();
            boolean citation = !result.citationOk;
            boolean answeredOutOfScope = "REFUSAL".equals(result.caseType) && !result.actualRefused;
            if (forbidden) {
                forbiddenViolations++;
            }
            if (citation) {
                citationViolations++;
            }
            if (forbidden || citation || answeredOutOfScope) {
                hallucinations++;
            }
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("answerPointsTotal", pointsTotal);
        metrics.put("answerPointsHit", pointsHit);
        metrics.put("answerPointRate", Metrics.round(Metrics.ratio(pointsHit, pointsTotal)));
        metrics.put("forbiddenViolationCount", forbiddenViolations);
        metrics.put("citationViolationCount", citationViolations);
        metrics.put("hallucinationCount", hallucinations);
        metrics.put("hallucinationRate", Metrics.round(Metrics.ratio(hallucinations, results.size())));
        return metrics;
    }

    /** 归因分布：每个失败层各有多少条 */
    private static Map<String, Object> attributionOf(List<EvalRunner.Result> results) {
        Map<String, Object> attribution = new LinkedHashMap<>();
        for (Attributor.Layer layer : Attributor.Layer.values()) {
            if (layer == Attributor.Layer.NONE) {
                continue;
            }
            int count = 0;
            for (EvalRunner.Result result : results) {
                if (result.layer == layer) {
                    count++;
                }
            }
            attribution.put(layer.name(), count);
        }
        return attribution;
    }
}
