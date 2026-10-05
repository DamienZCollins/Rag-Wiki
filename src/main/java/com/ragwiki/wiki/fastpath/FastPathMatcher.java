package com.ragwiki.wiki.fastpath;

import com.ragwiki.wiki.schema.FastEntry;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 命中判定。两级，都零模型调用、零向量检索。
 *
 * <ol>
 *   <li>精确匹配：规范化后的问题文本完全相同</li>
 *   <li>关键词匹配：条目的关键词全部出现在问题里，且置信度不低于阈值</li>
 * </ol>
 *
 * <p>关键词要求**全部命中**而不是命中其一：宁可漏判交给 RAG，也不要把「运费险怎么赔」
 * 错配到「运费收取规则」上——快通道答错的代价比慢通道多花点 token 大得多。
 */
@Component
public class FastPathMatcher {

    private final FastPathProperties properties;

    public FastPathMatcher(FastPathProperties properties) {
        this.properties = properties;
    }

    public enum MatchType {
        EXACT,
        KEYWORD,
        NONE
    }

    public record MatchResult(MatchType type, FastEntry entry) {

        public boolean hit() {
            return type != MatchType.NONE;
        }

        static MatchResult miss() {
            return new MatchResult(MatchType.NONE, null);
        }
    }

    public MatchResult match(String question, List<FastEntry> candidates) {
        String normalized = FastPathText.normalize(question);
        if (normalized.isEmpty()) {
            return MatchResult.miss();
        }

        double threshold = properties.minConfidence();

        for (FastEntry entry : candidates) {
            if (!aboveThreshold(entry, threshold)) {
                continue;
            }
            if (FastPathText.normalize(entry.getQuestion()).equals(normalized)) {
                return new MatchResult(MatchType.EXACT, entry);
            }
        }

        FastEntry best = null;
        int bestScore = -1;
        for (FastEntry entry : candidates) {
            if (!aboveThreshold(entry, threshold)) {
                continue;
            }
            List<String> keywords = entry.keywordList();
            if (!discriminative(keywords) || !keywords.stream().allMatch(normalized::contains)) {
                continue;
            }
            // 关键词越长越具体，命中多个比命中一个好
            int score = keywords.stream().mapToInt(String::length).sum();
            if (score > bestScore) {
                best = entry;
                bestScore = score;
            }
        }
        return best == null ? MatchResult.miss() : new MatchResult(MatchType.KEYWORD, best);
    }

    /**
     * 关键词要有区分度，否则会大面积误判。
     *
     * <p>反例：「运费计价重量与续重单位」的关键词只有一个「运费」，
     * 那「退货运费谁承担」「运费险怎么赔」都会命中它——全是错的。
     * 所以要求至少两个关键词，或者有一个长度 ≥ 3 的关键词（长词本身就够具体）。
     */
    static boolean discriminative(List<String> keywords) {
        return keywords.size() >= 2 || keywords.stream().anyMatch(keyword -> keyword.length() >= 3);
    }

    private boolean aboveThreshold(FastEntry entry, double threshold) {
        return entry.getConfidence() != null && entry.getConfidence().doubleValue() >= threshold;
    }
}
