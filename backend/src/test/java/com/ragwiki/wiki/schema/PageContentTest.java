package com.ragwiki.wiki.schema;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 条目正文的渲染与解析。格式固定成四段，Git 里才好 diff，lint 才能按段定位。
 */
class PageContentTest {

    private static WikiPage page() {
        WikiPage page = new WikiPage();
        page.setPageId("运费补贴上限");
        page.setTitle("运费补贴上限");
        page.setVersion("v2025.1");
        page.setStatus(WikiPageStatus.ACTIVE);
        page.setEffectiveAt(LocalDate.of(2025, 1, 1));
        page.setSources(WikiPage.sourcesJson(List.of("policy-freight-rule-v2025")));
        page.setUpdatedAt(Instant.parse("2026-01-02T03:04:05Z"));
        return page;
    }

    @Test
    void renderAndParseRoundTripsAllSections() {
        PageContent content = new PageContent(
                "单笔订单运费最高补贴 20 元。",
                List.of("仅限普通快递", "不含大件配送费"),
                List.of("偏远地区不适用"),
                List.of("运费收取与承担规则", "会员权益"));

        PageContent parsed = PageContent.parse(content.render(page()));

        assertThat(parsed).isEqualTo(content);
    }

    @Test
    void renderWritesMetadataFrontmatter() {
        String markdown = new PageContent("摘要", List.of(), List.of(), List.of()).render(page());

        assertThat(markdown).startsWith("---\n");
        assertThat(markdown).contains("page_id: 运费补贴上限");
        assertThat(markdown).contains("status: ACTIVE");
        assertThat(markdown).contains("  - policy-freight-rule-v2025");
        assertThat(markdown).contains("## 摘要");
        assertThat(markdown).contains("## 关联条目");
    }

    @Test
    void emptyListsRenderAsEmptyMarkAndParseBackToEmpty() {
        String markdown = new PageContent("摘要", List.of(), List.of(), List.of()).render(page());

        assertThat(markdown).contains("- 无");
        PageContent parsed = PageContent.parse(markdown);
        assertThat(parsed.conditions()).isEmpty();
        assertThat(parsed.exceptions()).isEmpty();
        assertThat(parsed.relatedTitles()).isEmpty();
    }

    @Test
    void relatedTitlesAreWrappedInDoubleBracketsOnRender() {
        String markdown = new PageContent("摘要", List.of(), List.of(), List.of("会员权益")).render(page());

        assertThat(markdown).contains("- [[会员权益]]");
    }

    @Test
    void parseIgnoresUnknownSectionsAndFreeTextBeforeFirstHeading() {
        String markdown = """
                ---
                page_id: 运费规则
                title: 运费规则
                status: ACTIVE
                sources:
                  - policy-freight-rule-v2025
                ---

                这段是游离文字，不属于任何一段

                ## 摘要

                结论一。

                ## 其它段落

                这一段会被忽略

                ## 适用条件

                - 条件一
                """;

        PageContent parsed = PageContent.parse(markdown);

        assertThat(parsed.summary()).isEqualTo("结论一。");
        assertThat(parsed.conditions()).containsExactly("条件一");
        assertThat(parsed.exceptions()).isEmpty();
    }

    @Test
    void parseAcceptsRelatedTitlesWithoutBrackets() {
        String markdown = """
                ## 关联条目

                - 运费收取与承担规则
                """;

        assertThat(PageContent.parse(markdown).relatedTitles())
                .containsExactly("运费收取与承担规则");
    }

    @Test
    void emptyContentIsAllEmpty() {
        PageContent empty = PageContent.empty();

        assertThat(empty.summary()).isEmpty();
        assertThat(empty.conditions()).isEmpty();
        assertThat(empty.exceptions()).isEmpty();
        assertThat(empty.relatedTitles()).isEmpty();
    }
}
