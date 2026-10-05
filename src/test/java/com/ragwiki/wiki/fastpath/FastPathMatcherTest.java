package com.ragwiki.wiki.fastpath;

import com.ragwiki.wiki.schema.FastEntry;
import com.ragwiki.wiki.schema.FastEntryStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 命中判定。这个类用纯单测覆盖——快通道答错是直接答给用户的，比慢通道多花点 token 严重得多。
 */
class FastPathMatcherTest {

    private static final double THRESHOLD = 0.8;

    private final FastPathMatcher matcher = new FastPathMatcher(new FastPathProperties(
            "aftersale", java.time.Duration.ofMinutes(5), THRESHOLD, 0.9, 0.6,
            new FastPathProperties.Distill(3, 7)));

    private static FastEntry entry(String question, List<String> keywords, double confidence) {
        FastEntry entry = new FastEntry();
        entry.setEntryId(FastPathText.entryId(question));
        entry.setQuestion(question);
        entry.setAnswer("答案");
        entry.setKeywords(FastEntry.keywordsJson(keywords));
        entry.setConfidence(BigDecimal.valueOf(confidence));
        entry.setStatus(FastEntryStatus.ACTIVE);
        return entry;
    }

    @Test
    void exactMatchIgnoresPunctuationAndSpaces() {
        List<FastEntry> candidates = List.of(entry("退货运费谁出？", List.of("退货", "运费"), 0.9));

        assertThat(matcher.match("退货运费谁出", candidates).type())
                .isEqualTo(FastPathMatcher.MatchType.EXACT);
        assertThat(matcher.match("退货 运费谁出？", candidates).type())
                .isEqualTo(FastPathMatcher.MatchType.EXACT);
    }

    @Test
    void keywordMatchRequiresAllKeywords() {
        List<FastEntry> candidates = List.of(entry("退货运费承担方划分", List.of("退货", "运费"), 0.9));

        assertThat(matcher.match("退货的运费谁承担", candidates).hit()).isTrue();
        // 只命中一个关键词不算
        assertThat(matcher.match("运费险怎么赔", candidates).hit()).isFalse();
        assertThat(matcher.match("退货要几天", candidates).hit()).isFalse();
    }

    @Test
    void singleShortKeywordIsNotDiscriminative() {
        // 这条条目的关键词只有一个「运费」，任何带「运费」的问题都会命中它——
        // 必须被区分度规则挡住，否则「退货运费谁承担」会被它答成「取重量与体积重量的较大者」
        List<FastEntry> candidates = List.of(entry("运费计价重量与续重单位", List.of("运费"), 0.9));

        assertThat(matcher.match("退货运费一般谁承担？", candidates).hit()).isFalse();
    }

    @Test
    void singleLongKeywordIsDiscriminative() {
        List<FastEntry> candidates = List.of(entry("偏远地区附加费", List.of("附加费"), 0.9));

        assertThat(matcher.match("偏远地区要收附加费吗", candidates).hit()).isTrue();
    }

    @Test
    void picksMostSpecificEntryWhenSeveralMatch() {
        FastEntry weak = entry("运费计价重量与续重单位", List.of("运费"), 0.9);
        FastEntry strong = entry("退货运费承担方划分", List.of("退货运费", "运费"), 0.9);

        FastPathMatcher.MatchResult result = matcher.match("退货运费怎么算", List.of(weak, strong));

        assertThat(result.hit()).isTrue();
        assertThat(result.entry().getQuestion()).isEqualTo("退货运费承担方划分");
    }

    @Test
    void ignoresEntriesBelowConfidenceThreshold() {
        // 沉淀条目置信度 0.6，低于阈值 0.8，即便关键词全中也不判命中
        List<FastEntry> candidates = List.of(entry("发票丢了怎么办", List.of("发票"), 0.6));

        assertThat(matcher.match("发票丢了怎么办", candidates).hit()).isFalse();
    }

    @Test
    void missesWhenNothingMatches() {
        assertThat(matcher.match("帮我订一张明天去上海的机票", List.of()).hit()).isFalse();
        assertThat(matcher.match("", List.of(entry("包邮门槛", List.of("包邮"), 0.9))).hit()).isFalse();
    }
}
