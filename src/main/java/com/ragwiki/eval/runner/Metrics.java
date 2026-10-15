package com.ragwiki.eval.runner;

import java.util.ArrayList;
import java.util.List;

/**
 * 指标口径。全部在这里实现，报告里写的公式与这里的代码必须一致。
 *
 * <p>口径写死在一处的原因：同一份数据用不同口径算出来的数会差出好几个百分点，
 * 报告上写的「Recall@8 提升了多少」如果没人能复算，那个数字就没有意义。
 */
public final class Metrics {

    private Metrics() {
    }

    /**
     * 分位数，用最近秩法（nearest-rank）：取排序后第 ceil(p/100 * n) 个。
     *
     * <p>样本量小时 P99 就是最大值，报告中要标注样本量，不能拿 22 条用例的 P99 当稳定结论。
     */
    public static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    /** 比率，分母为 0 时返回 0 而不是 NaN——空集合的比率在报告里没有意义 */
    public static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    /** 保留四位小数的比率，写进报告前统一一次 */
    public static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    public static long sum(List<Integer> values) {
        long total = 0;
        for (Integer value : values) {
            if (value != null) {
                total += value;
            }
        }
        return total;
    }

    public static long mean(List<Integer> values) {
        return values.isEmpty() ? 0 : sum(values) / values.size();
    }
}
