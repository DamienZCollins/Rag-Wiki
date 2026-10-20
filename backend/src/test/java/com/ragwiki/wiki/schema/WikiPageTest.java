package com.ragwiki.wiki.schema;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 条目标题归一化与状态机。标题是概念的主键，版本后缀没剥干净就会让同一个概念越编越多。
 */
class WikiPageTest {

    @Test
    void normalizeTitleStripsVersionSuffixesRepeatedly() {
        assertThat(WikiPage.normalizeTitle("运费规则（v2024.3）（v2025.1）")).isEqualTo("运费规则");
        assertThat(WikiPage.normalizeTitle("运费规则（2025）")).isEqualTo("运费规则");
        assertThat(WikiPage.normalizeTitle("运费规则 v2025.1")).isEqualTo("运费规则 v2025.1");
    }

    @Test
    void normalizeTitleKeepsNonVersionParentheses() {
        // 「（含大件）」不是版本号，剥掉就把两个不同概念合并成一个了
        assertThat(WikiPage.normalizeTitle("运费规则（含大件）")).isEqualTo("运费规则（含大件）");
    }

    @Test
    void pageIdNormalizesPunctuationAndCase() {
        assertThat(WikiPage.pageIdOf("运费规则（含大件）")).isEqualTo("运费规则-含大件");
        assertThat(WikiPage.pageIdOf("  Freight Rule  ")).isEqualTo("freight-rule");
        assertThat(WikiPage.pageIdOf("运费 / 补贴")).isEqualTo("运费-补贴");
    }

    @Test
    void pageIdRejectsTitleThatNormalizesToNothing() {
        assertThatThrownBy(() -> WikiPage.pageIdOf("（）"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourcesJsonRoundTrips() {
        List<String> sources = List.of("policy-a", "policy-b");

        assertThat(WikiPage.sourcesJson(sources)).isEqualTo("[\"policy-a\",\"policy-b\"]");

        WikiPage page = new WikiPage();
        page.setSources(WikiPage.sourcesJson(sources));
        assertThat(page.sourceList()).containsExactly("policy-a", "policy-b");
    }

    @Test
    void emptySourcesParseToEmptyList() {
        WikiPage page = new WikiPage();
        assertThat(page.sourceList()).isEmpty();
        page.setSources("[]");
        assertThat(page.sourceList()).isEmpty();
    }

    @Test
    void retireMovesPageToRetiredWithSupersededBy() {
        WikiPage page = new WikiPage();
        page.setStatus(WikiPageStatus.ACTIVE);
        Instant now = Instant.parse("2026-01-02T03:04:05Z");

        page.retire("policy-freight-rule-v2025", now);

        assertThat(page.getStatus()).isEqualTo(WikiPageStatus.RETIRED);
        assertThat(page.getSupersededBy()).isEqualTo("policy-freight-rule-v2025");
        assertThat(page.getRetiredAt()).isEqualTo(now);
    }

    @Test
    void statusMachineOnlyAllowsForwardTransitions() {
        assertThat(WikiPageStatus.DRAFT.canTransitionTo(WikiPageStatus.ACTIVE)).isTrue();
        assertThat(WikiPageStatus.ACTIVE.canTransitionTo(WikiPageStatus.RETIRED)).isTrue();
        assertThat(WikiPageStatus.RETIRED.canTransitionTo(WikiPageStatus.ACTIVE)).isFalse();

        WikiPage retired = new WikiPage();
        retired.setStatus(WikiPageStatus.RETIRED);
        assertThatThrownBy(() -> retired.retire("policy-x", Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }
}
