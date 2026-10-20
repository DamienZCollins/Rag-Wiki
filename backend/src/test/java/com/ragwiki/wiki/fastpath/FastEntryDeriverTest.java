package com.ragwiki.wiki.fastpath;

import com.ragwiki.wiki.schema.FastEntry;
import com.ragwiki.wiki.schema.FastEntryStatus;
import com.ragwiki.wiki.schema.PageContent;
import com.ragwiki.wiki.schema.WikiPage;
import com.ragwiki.wiki.schema.WikiPageStatus;
import com.ragwiki.wiki.store.GitPageStore;
import com.ragwiki.wiki.store.WikiStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 从 Wiki 条目派生快通道候选：纯规则、零模型调用，重复执行幂等。
 */
class FastEntryDeriverTest {

    private static final FastPathProperties PROPERTIES = new FastPathProperties(
            "aftersale", Duration.ofMinutes(5), 0.8, 0.9, 0.6,
            new FastPathProperties.Distill(3, 7));

    @TempDir
    Path pageDir;

    private final WikiStore store = mock(WikiStore.class);
    private GitPageStore pageStore;
    private FastEntryDeriver deriver;

    @BeforeEach
    void setUp() {
        // @TempDir 在构造器之后才注入，不能写在字段初始化里
        pageStore = new GitPageStore(pageDir.toString());
        deriver = new FastEntryDeriver(store, pageStore, new FastPathLexicon(), PROPERTIES);
    }

    private static WikiPage page(String title) {
        WikiPage page = new WikiPage();
        page.setPageId(WikiPage.pageIdOf(title));
        page.setTitle(title);
        page.setStatus(WikiPageStatus.ACTIVE);
        page.setEffectiveAt(LocalDate.of(2025, 1, 1));
        return page;
    }

    private void writeContent(WikiPage page, String summary, List<String> conditions) {
        pageStore.write(page, new PageContent(summary, conditions, List.of(), List.of()));
    }

    @Test
    void derivesCandidateFromSummaryAndTitle() {
        WikiPage page = page("运费补贴上限");
        writeContent(page, "单笔订单运费最高补贴 20 元。", List.of("仅限普通快递"));
        when(store.listPages(WikiPageStatus.ACTIVE)).thenReturn(List.of(page));
        when(store.findFastEntry(any())).thenReturn(Optional.empty());

        FastEntryDeriver.Report report = deriver.derive();

        assertThat(report.scannedPages()).isEqualTo(1);
        assertThat(report.created()).isEqualTo(1);

        ArgumentCaptor<FastEntry> captor = ArgumentCaptor.forClass(FastEntry.class);
        verify(store).insertFastEntry(captor.capture());
        FastEntry entry = captor.getValue();
        assertThat(entry.getQuestion()).isEqualTo("运费补贴上限是多少？");
        assertThat(entry.getAnswer()).isEqualTo("单笔订单运费最高补贴 20 元。");
        assertThat(entry.getKeywords()).contains("运费补贴");
        assertThat(entry.getConditions()).isEqualTo("仅限普通快递");
        assertThat(entry.getStatus()).isEqualTo(FastEntryStatus.CANDIDATE);
        assertThat(entry.getOrigin()).isEqualTo(FastEntry.ORIGIN_DERIVED);
        assertThat(entry.getSourcePageId()).isEqualTo(page.getPageId());
        assertThat(entry.getConfidence()).isEqualByComparingTo("0.9");
        assertThat(entry.getEntryId()).isEqualTo(FastPathText.entryId("运费补贴上限是多少？"));
    }

    @Test
    void isIdempotentWhenEntryAlreadyExists() {
        WikiPage page = page("运费补贴上限");
        writeContent(page, "单笔订单运费最高补贴 20 元。", List.of());
        when(store.listPages(WikiPageStatus.ACTIVE)).thenReturn(List.of(page));
        when(store.findFastEntry(FastPathText.entryId("运费补贴上限是多少？")))
                .thenReturn(Optional.of(new FastEntry()));

        FastEntryDeriver.Report report = deriver.derive();

        assertThat(report.created()).isZero();
        assertThat(report.skipped()).isEqualTo(1);
        verify(store, never()).insertFastEntry(any());
    }

    @Test
    void skipsPageWithTooShortSummary() {
        WikiPage page = page("运费规则");
        writeContent(page, "短", List.of());
        when(store.listPages(WikiPageStatus.ACTIVE)).thenReturn(List.of(page));

        FastEntryDeriver.Report report = deriver.derive();

        assertThat(report.created()).isZero();
        assertThat(report.skipped()).isEqualTo(1);
        verify(store, never()).insertFastEntry(any());
    }

    @Test
    void questionTemplateFollowsTitleSuffix() {
        assertThat(FastEntryDeriver.toQuestion("包邮门槛")).isEqualTo("包邮门槛是多少？");
        assertThat(FastEntryDeriver.toQuestion("退款时效")).isEqualTo("退款时效是多久？");
        assertThat(FastEntryDeriver.toQuestion("运费承担规则")).isEqualTo("运费承担规则是怎么规定的？");
        assertThat(FastEntryDeriver.toQuestion("举证条件")).isEqualTo("举证条件有哪些？");
        assertThat(FastEntryDeriver.toQuestion("会员权益")).isEqualTo("会员权益是什么？");
    }
}
