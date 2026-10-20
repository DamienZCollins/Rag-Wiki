package com.ragwiki.wiki.compile;

import com.ragwiki.wiki.schema.PageContent;
import com.ragwiki.wiki.schema.WikiPage;
import com.ragwiki.wiki.schema.WikiPageStatus;
import com.ragwiki.wiki.store.WikiStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 双链生成：正文里的关联标题落成链记录，目标不存在时登记为待创建，目标建出来后转正。
 */
class LinkBuilderTest {

    private final WikiStore store = mock(WikiStore.class);
    private final LinkBuilder linkBuilder = new LinkBuilder(store);

    private static WikiPage page(String title) {
        WikiPage page = new WikiPage();
        page.setPageId(WikiPage.pageIdOf(title));
        page.setTitle(title);
        page.setStatus(WikiPageStatus.ACTIVE);
        return page;
    }

    private static PageContent contentRelatedTo(String... titles) {
        return new PageContent("摘要", List.of(), List.of(), List.of(titles));
    }

    @Test
    void recordsExistingTargetAsConnectedAndMissingTargetAsPending() {
        WikiPage source = page("运费规则");
        when(store.findPage(WikiPage.pageIdOf("运费补贴上限"))).thenReturn(Optional.of(page("运费补贴上限")));
        when(store.findPage(WikiPage.pageIdOf("会员权益"))).thenReturn(Optional.empty());

        linkBuilder.rebuild(source, contentRelatedTo("运费补贴上限", "会员权益"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WikiStore.LinkTarget>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).replaceLinks(eq(source.getPageId()), captor.capture());
        assertThat(captor.getValue())
                .extracting(WikiStore.LinkTarget::toTitle, WikiStore.LinkTarget::toPageId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("运费补贴上限", WikiPage.pageIdOf("运费补贴上限")),
                        org.assertj.core.groups.Tuple.tuple("会员权益", null));
    }

    @Test
    void dropsSelfLinksAndBlankTitles() {
        WikiPage source = page("运费规则");

        linkBuilder.rebuild(source, contentRelatedTo("运费规则", "  ", "运费补贴上限"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WikiStore.LinkTarget>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).replaceLinks(eq(source.getPageId()), captor.capture());
        assertThat(captor.getValue()).extracting(WikiStore.LinkTarget::toTitle)
                .containsExactly("运费补贴上限");
    }

    @Test
    void normalizesVersionSuffixSoSameConceptStaysOneLink() {
        WikiPage source = page("运费规则");

        linkBuilder.rebuild(source, contentRelatedTo("运费补贴上限（v2025.1）"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WikiStore.LinkTarget>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).replaceLinks(eq(source.getPageId()), captor.capture());
        assertThat(captor.getValue()).extracting(WikiStore.LinkTarget::toTitle)
                .containsExactly("运费补贴上限");
    }

    @Test
    void resolveIncomingTurnsPendingLinksIntoConnected() {
        WikiPage page = page("会员权益");

        linkBuilder.resolveIncoming(page);

        verify(store).resolvePendingLinks(page.getPageId(), "会员权益");
    }

    @Test
    void rebuildReplacesAllLinksInsteadOfAppending() {
        WikiPage source = page("运费规则");

        linkBuilder.rebuild(source, contentRelatedTo("运费补贴上限"));
        // 正文改过之后关联标题没了，重建应该写空列表，而不是保留上一次的链接
        linkBuilder.rebuild(source, PageContent.empty());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WikiStore.LinkTarget>> captor = ArgumentCaptor.forClass(List.class);
        verify(store, org.mockito.Mockito.times(2)).replaceLinks(eq(source.getPageId()), captor.capture());
        assertThat(captor.getAllValues().get(1)).isEmpty();
        // 整体替换靠 replaceLinks 自己先删后插，不该再动全库链接
        verify(store, org.mockito.Mockito.never()).deleteAllLinks();
    }
}
