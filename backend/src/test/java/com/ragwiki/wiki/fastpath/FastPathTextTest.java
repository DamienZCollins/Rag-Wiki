package com.ragwiki.wiki.fastpath;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快通道的文本规则：问题规范化、条目 id 幂等、术语抽取。
 */
class FastPathTextTest {

    @Test
    void normalizeDropsPunctuationAndWhitespaceCaseInsensitively() {
        assertThat(FastPathText.normalize("退货运费谁出？")).isEqualTo("退货运费谁出");
        assertThat(FastPathText.normalize("退货 运费谁出。")).isEqualTo("退货运费谁出");
        assertThat(FastPathText.normalize("  SO20250901001 的订单 ")).isEqualTo("so20250901001的订单");
        assertThat(FastPathText.normalize(null)).isEmpty();
    }

    @Test
    void entryIdIsStableAcrossPunctuationVariants() {
        String base = FastPathText.entryId("退货运费谁出");

        assertThat(FastPathText.entryId("退货运费谁出？")).isEqualTo(base);
        assertThat(FastPathText.entryId("退货 运费谁出。")).isEqualTo(base);
        assertThat(FastPathText.entryId("运费谁承担")).isNotEqualTo(base);
        assertThat(base).startsWith("fp-").hasSize(19);
    }

    @Test
    void lexiconPicksLongestTermsFirstUpToThree() {
        FastPathLexicon lexicon = new FastPathLexicon();

        // 「运费险」不能被更短的「运费」抢走
        assertThat(lexicon.extract("运费险理赔规则")).contains("运费险");
        assertThat(lexicon.extract("退货运费与包邮门槛")).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void lexiconFallsBackToTitleWhenNoTermMatches() {
        FastPathLexicon lexicon = new FastPathLexicon();

        assertThat(lexicon.extract("客服响应时间")).containsExactly("客服响应时间");
        assertThat(lexicon.extract("  ")).isEmpty();
    }

    @Test
    void lexiconUsesQuestionItselfWhenNoTermMatches() {
        FastPathLexicon lexicon = new FastPathLexicon();

        List<String> keywords = lexicon.extractFromQuestion("你们周末上班吗");

        assertThat(keywords).containsExactly("你们周末上班吗");
        assertThat(lexicon.extractFromQuestion("退货运费怎么算")).doesNotContain("退货运费怎么算");
    }

    @Test
    void lexiconKeepsOrderAndDeduplicates() {
        List<String> terms = new FastPathLexicon().terms();

        assertThat(terms).doesNotHaveDuplicates();
        assertThat(terms).contains("七天无理由", "运费险", "转人工");
    }
}
