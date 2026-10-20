package com.ragwiki.eval.runner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指标口径。报告里写的公式要与这里的实现一致，否则数字没法复算。
 */
class MetricsTest {

    @Test
    void percentileUsesNearestRank() {
        List<Long> values = List.of(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L);

        assertThat(Metrics.percentile(values, 50)).isEqualTo(50);
        assertThat(Metrics.percentile(values, 99)).isEqualTo(100);
        assertThat(Metrics.percentile(values, 30)).isEqualTo(30);
        assertThat(Metrics.percentile(List.of(7L), 99)).isEqualTo(7);
        assertThat(Metrics.percentile(List.of(), 99)).isZero();
    }

    @Test
    void percentileDoesNotRelyOnInputOrder() {
        List<Long> ascending = List.of(5L, 10L, 15L, 20L);
        List<Long> shuffled = List.of(20L, 5L, 15L, 10L);

        assertThat(Metrics.percentile(shuffled, 50)).isEqualTo(Metrics.percentile(ascending, 50));
        assertThat(Metrics.percentile(shuffled, 99)).isEqualTo(20);
    }

    @Test
    void ratioReturnsZeroForEmptyDenominator() {
        assertThat(Metrics.ratio(0, 0)).isZero();
        assertThat(Metrics.ratio(3, 4)).isEqualTo(0.75);
    }

    @Test
    void roundKeepsFourDecimals() {
        assertThat(Metrics.round(1.0 / 3)).isEqualTo(0.3333);
        assertThat(Metrics.round(0.123456)).isEqualTo(0.1235);
    }

    @Test
    void sumAndMeanSkipNulls() {
        List<Integer> values = java.util.Arrays.asList(10, null, 20, null);

        assertThat(Metrics.sum(values)).isEqualTo(30);
        // 均值分母是列表长度，空值按 0 计
        assertThat(Metrics.mean(values)).isEqualTo(7);
        assertThat(Metrics.sum(List.of())).isZero();
        assertThat(Metrics.mean(List.of())).isZero();
    }
}
