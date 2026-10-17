package com.ragwiki.eval.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归门禁：比率型指标掉出容差判退化，越低越好的指标上升判退化。
 */
class RegressionGateTest {

    @TempDir
    Path tempDir;

    private RegressionGate gate(Path baselinePath, double tolerance) {
        return new RegressionGate(new ObjectMapper(), baselinePath.toString(), tolerance);
    }

    private Path baselineFile() {
        return tempDir.resolve("baseline.json");
    }

    private void writeBaseline(RegressionGate gate, Map<String, Double> metrics) {
        gate.writeBaseline(new RegressionGate.Baseline("run-base", "基线", metrics));
    }

    @Test
    void passesWhenMetricsWithinTolerance() {
        RegressionGate gate = gate(baselineFile(), 0.02);
        writeBaseline(gate, Map.of("passRate", 0.90, "recallRate", 0.95));

        // 掉 1 个百分点，在 2% 容差内
        RegressionGate.GateReport report = gate.compare(
                Map.of("passRate", 0.89, "recallRate", 0.95), List.of());

        assertThat(report.passed()).isTrue();
        assertThat(report.regressed()).isEmpty();
    }

    @Test
    void failsWhenMetricDropsBeyondTolerance() {
        RegressionGate gate = gate(baselineFile(), 0.02);
        writeBaseline(gate, Map.of("passRate", 0.90, "recallRate", 0.95));

        RegressionGate.GateReport report = gate.compare(
                Map.of("passRate", 0.80, "recallRate", 0.95), List.of("policy-001"));

        assertThat(report.passed()).isFalse();
        assertThat(report.regressed()).containsExactly("passRate");
        // 失败用例一并带出来，便于定位是哪条退化
        assertThat(report.failedCases()).containsExactly("policy-001");
    }

    @Test
    void hallucinationRateRisingIsRegression() {
        RegressionGate gate = gate(baselineFile(), 0.02);
        writeBaseline(gate, Map.of("hallucinationRate", 0.05));

        RegressionGate.GateReport worse = gate.compare(Map.of("hallucinationRate", 0.10), List.of());
        assertThat(worse.passed()).isFalse();
        assertThat(worse.regressed()).containsExactly("hallucinationRate");

        RegressionGate.GateReport better = gate.compare(Map.of("hallucinationRate", 0.03), List.of());
        assertThat(better.passed()).isTrue();
    }

    @Test
    void ignoresMetricsMissingFromBaseline() {
        RegressionGate gate = gate(baselineFile(), 0.02);
        writeBaseline(gate, Map.of("passRate", 0.90));

        RegressionGate.GateReport report = gate.compare(
                Map.of("passRate", 0.90, "newMetric", 0.1), List.of());

        assertThat(report.comparisons()).hasSize(1);
        assertThat(report.passed()).isTrue();
    }
}
