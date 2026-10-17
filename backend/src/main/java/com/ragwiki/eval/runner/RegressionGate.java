package com.ragwiki.eval.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回归门禁：拿基线指标与当前批次比，关键指标掉出容差就判失败。
 *
 * <p>比较的是**比率型指标**（召回率、通过率、拒答正确率、MRR、要点命中率），
 * 不比绝对数：用例数会变，token 与延迟受机器与模型波动影响，
 * 拿它们当门禁会天天误报，反而让人把门禁关掉。
 *
 * <p>容差可配，默认 0.02。不设零容差是因为 LLM 有采样波动，
 * 同样的输入重跑一次通过率就会差一两条，零容差的门禁只会被绕过。
 */
@Component
public class RegressionGate {

    /**
     * 参与门禁的指标，全部是 0~1 的比率。
     *
     * <p>幻觉率是「越低越好」的指标，方向与其他几项相反，判定时单独处理。
     */
    private static final List<String> GUARDED = List.of(
            "passRate", "recallRate", "mrr", "top1Rate", "refusalCorrectRate", "answerPointRate");

    /** 越低越好的指标，上升即退化 */
    private static final List<String> LOWER_IS_BETTER = List.of("hallucinationRate");

    private final ObjectMapper objectMapper;
    private final Path baselinePath;
    private final double tolerance;

    public RegressionGate(ObjectMapper objectMapper,
                          @Value("${ragwiki.eval.baseline:eval/baseline.json}") String baselinePath,
                          @Value("${ragwiki.eval.tolerance:0.02}") double tolerance) {
        this.objectMapper = objectMapper;
        this.baselinePath = Path.of(baselinePath);
        this.tolerance = tolerance;
    }

    public record Baseline(String runId, String label, Map<String, Double> metrics) {
    }

    public record Comparison(String metric, double baseline, double current, double delta,
                             boolean regressed) {
    }

    public record GateReport(boolean passed, String baselineRunId, double tolerance,
                             List<Comparison> comparisons, List<String> regressed,
                             /** 当前批次里失败、且归因层不是兜底的用例，供定位退化点 */
                             List<String> failedCases) {
    }

    /** 读基线。文件不存在时报错，不静默按通过处理——没有基线的门禁等于没门禁 */
    public Baseline loadBaseline() {
        if (!Files.exists(baselinePath)) {
            throw new IllegalStateException("基线文件不存在：" + baselinePath.toAbsolutePath()
                    + "，先跑一次评测并写入基线");
        }
        try {
            String json = Files.readString(baselinePath, StandardCharsets.UTF_8);
            return objectMapper.readValue(json, Baseline.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取基线失败：" + baselinePath, e);
        }
    }

    /** 把一批的指标固化成基线 */
    public void writeBaseline(Baseline baseline) {
        try {
            Path parent = baselinePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(baselinePath,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(baseline),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入基线失败：" + baselinePath, e);
        }
    }

    /**
     * 比一批指标与基线。
     *
     * @param currentMetrics 当前批次的 metrics（取 Baseline 里同名键）
     * @param failedCases    当前批次失败用例的 caseId
     */
    public GateReport compare(Map<String, Object> currentMetrics, List<String> failedCases) {
        Baseline baseline = loadBaseline();
        List<Comparison> comparisons = new ArrayList<>();
        List<String> regressed = new ArrayList<>();

        for (String metric : GUARDED) {
            Double baselineValue = baseline.metrics().get(metric);
            if (baselineValue == null) {
                continue;
            }
            double currentValue = asDouble(currentMetrics.get(metric));
            double delta = Metrics.round(currentValue - baselineValue);
            // 比率型指标掉出容差即退化
            boolean isRegressed = delta < -tolerance;
            comparisons.add(new Comparison(metric, baselineValue, currentValue, delta, isRegressed));
            if (isRegressed) {
                regressed.add(metric);
            }
        }

        for (String metric : LOWER_IS_BETTER) {
            Double baselineValue = baseline.metrics().get(metric);
            if (baselineValue == null) {
                continue;
            }
            double currentValue = asDouble(currentMetrics.get(metric));
            double delta = Metrics.round(currentValue - baselineValue);
            // 越低越好的指标，上升即退化
            boolean isRegressed = delta > tolerance;
            comparisons.add(new Comparison(metric, baselineValue, currentValue, delta, isRegressed));
            if (isRegressed) {
                regressed.add(metric);
            }
        }

        return new GateReport(regressed.isEmpty(), baseline.runId(), tolerance,
                List.copyOf(comparisons), List.copyOf(regressed), List.copyOf(failedCases));
    }

    /** 从批次指标里抽出参与门禁的那几个，用来写基线 */
    public Map<String, Double> pickGuarded(Map<String, Object> metrics) {
        Map<String, Double> picked = new LinkedHashMap<>();
        for (String metric : GUARDED) {
            if (metrics.containsKey(metric)) {
                picked.put(metric, asDouble(metrics.get(metric)));
            }
        }
        for (String metric : LOWER_IS_BETTER) {
            if (metrics.containsKey(metric)) {
                picked.put(metric, asDouble(metrics.get(metric)));
            }
        }
        return picked;
    }

    private static double asDouble(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(value.toString());
    }
}
