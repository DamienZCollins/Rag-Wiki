package com.ragwiki.wiki.fastpath;

import com.ragwiki.wiki.schema.FastEntry;
import com.ragwiki.wiki.schema.FastEntryStatus;
import com.ragwiki.wiki.schema.WikiPage;
import com.ragwiki.wiki.schema.WikiPageStatus;
import com.ragwiki.wiki.store.WikiStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上线校验。这里是快通道的强制关卡：没有来源、来源失效的条目不许上线，
 * 已上线的条目来源一失效就要同步下线。
 */
class EntryPublisherTest {

    private final WikiStore store = mock(WikiStore.class);
    private final EntryPublisher publisher = new EntryPublisher(store);

    private static WikiPage page(String title, WikiPageStatus status) {
        WikiPage page = new WikiPage();
        page.setPageId(WikiPage.pageIdOf(title));
        page.setTitle(title);
        page.setStatus(status);
        return page;
    }

    private static FastEntry candidate(String entryId, String question, String answer,
                                       List<String> keywords, String sourcePageId) {
        FastEntry entry = new FastEntry();
        entry.setEntryId(entryId);
        entry.setScene("aftersale");
        entry.setQuestion(question);
        entry.setAnswer(answer);
        entry.setKeywords(FastEntry.keywordsJson(keywords));
        entry.setConfidence(BigDecimal.valueOf(0.9));
        entry.setStatus(FastEntryStatus.CANDIDATE);
        entry.setOrigin(FastEntry.ORIGIN_DERIVED);
        entry.setSourcePageId(sourcePageId);
        entry.setHitCount(0);
        return entry;
    }

    @Test
    void publishesCandidateWithActiveSource() {
        WikiPage source = page("运费补贴上限", WikiPageStatus.ACTIVE);
        FastEntry entry = candidate("fp-1", "运费补贴上限是多少？", "单笔最高补贴 20 元。",
                List.of("运费补贴", "上限"), source.getPageId());
        when(store.listFastEntries(FastEntryStatus.CANDIDATE)).thenReturn(List.of(entry));
        when(store.listFastEntries(FastEntryStatus.ACTIVE)).thenReturn(List.of());
        when(store.findPage(source.getPageId())).thenReturn(Optional.of(source));

        EntryPublisher.PublishReport report = publisher.publish();

        assertThat(report.published()).isEqualTo(1);
        assertThat(report.rejected()).isZero();
        assertThat(entry.getStatus()).isEqualTo(FastEntryStatus.ACTIVE);
        verify(store).updateFastEntry(entry);
    }

    @Test
    void rejectsDistilledCandidateWithoutSource() {
        // 沉淀条目没有出处，只能停在候选态，必须人工绑定来源
        FastEntry entry = candidate("fp-2", "大闸蟹能退吗？", "", List.of("生鲜"), null);
        entry.setOrigin(FastEntry.ORIGIN_DISTILLED);
        when(store.listFastEntries(FastEntryStatus.CANDIDATE)).thenReturn(List.of(entry));
        when(store.listFastEntries(FastEntryStatus.ACTIVE)).thenReturn(List.of());

        EntryPublisher.PublishReport report = publisher.publish();

        assertThat(report.published()).isZero();
        assertThat(report.rejections()).singleElement().asString().contains("没有来源");
        assertThat(entry.getStatus()).isEqualTo(FastEntryStatus.CANDIDATE);
        verify(store, never()).updateFastEntry(entry);
    }

    @Test
    void rejectsCandidateWhoseSourceIsRetired() {
        WikiPage source = page("旧运费规则", WikiPageStatus.RETIRED);
        FastEntry entry = candidate("fp-3", "包邮门槛是多少？", "79 元。",
                List.of("包邮", "门槛"), source.getPageId());
        when(store.listFastEntries(FastEntryStatus.CANDIDATE)).thenReturn(List.of(entry));
        when(store.listFastEntries(FastEntryStatus.ACTIVE)).thenReturn(List.of());
        when(store.findPage(source.getPageId())).thenReturn(Optional.of(source));

        EntryPublisher.PublishReport report = publisher.publish();

        assertThat(report.published()).isZero();
        assertThat(report.rejections()).singleElement().asString().contains("已失效");
        assertThat(entry.getStatus()).isEqualTo(FastEntryStatus.CANDIDATE);
    }

    @Test
    void rejectsCandidateWithoutKeywordsOrAnswer() {
        WikiPage source = page("运费规则", WikiPageStatus.ACTIVE);
        FastEntry noKeywords = candidate("fp-4", "运费怎么算？", "按重量取大。", List.of(), source.getPageId());
        FastEntry noAnswer = candidate("fp-5", "运费谁承担？", "  ", List.of("运费", "承担"), source.getPageId());
        when(store.listFastEntries(FastEntryStatus.CANDIDATE)).thenReturn(List.of(noKeywords, noAnswer));
        when(store.listFastEntries(FastEntryStatus.ACTIVE)).thenReturn(List.of());
        when(store.findPage(source.getPageId())).thenReturn(Optional.of(source));

        EntryPublisher.PublishReport report = publisher.publish();

        assertThat(report.published()).isZero();
        assertThat(report.rejections()).hasSize(2);
        assertThat(report.rejections()).anyMatch(text -> text.contains("没有关键词"));
        assertThat(report.rejections()).anyMatch(text -> text.contains("没有答案"));
    }

    @Test
    void offlinesActiveEntriesWhenSourcePageWasRetired() {
        WikiPage retiredSource = page("旧运费规则", WikiPageStatus.RETIRED);
        FastEntry entry = candidate("fp-6", "包邮门槛是多少？", "79 元。",
                List.of("包邮", "门槛"), retiredSource.getPageId());
        entry.setStatus(FastEntryStatus.ACTIVE);
        when(store.listFastEntries(FastEntryStatus.ACTIVE)).thenReturn(List.of(entry));
        when(store.listFastEntries(FastEntryStatus.CANDIDATE)).thenReturn(List.of());
        when(store.findPage(retiredSource.getPageId())).thenReturn(Optional.of(retiredSource));

        EntryPublisher.PublishReport report = publisher.publish();

        assertThat(report.offlined()).isEqualTo(1);
        assertThat(report.offlinedEntries()).containsExactly("包邮门槛是多少？");
        assertThat(entry.getStatus()).isEqualTo(FastEntryStatus.OFFLINE);
        verify(store).updateFastEntry(entry);
    }

    @Test
    void validateReturnsNullOnlyWhenEverythingIsPresent() {
        WikiPage source = page("运费规则", WikiPageStatus.ACTIVE);
        when(store.findPage(source.getPageId())).thenReturn(Optional.of(source));

        assertThat(publisher.validate(candidate("fp-7", "问题", "答案", List.of("运费"), source.getPageId())))
                .isNull();
    }
}
