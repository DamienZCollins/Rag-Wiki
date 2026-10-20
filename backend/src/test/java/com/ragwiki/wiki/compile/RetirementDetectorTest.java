package com.ragwiki.wiki.compile;

import com.ragwiki.wiki.schema.SourceDoc;
import com.ragwiki.wiki.schema.WikiPage;
import com.ragwiki.wiki.schema.WikiPageStatus;
import com.ragwiki.wiki.store.WikiStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 失效检测的两条规则：被新版本取代的条目、来源全部失效的条目。
 */
class RetirementDetectorTest {

    private final WikiStore store = mock(WikiStore.class);
    private final RetirementDetector detector = new RetirementDetector(store);

    private static WikiPage page(String title, WikiPageStatus status, String... sources) {
        WikiPage page = new WikiPage();
        page.setPageId(WikiPage.pageIdOf(title));
        page.setTitle(title);
        page.setStatus(status);
        page.setVersion("v2024.3");
        page.setSources(WikiPage.sourcesJson(List.of(sources)));
        return page;
    }

    private static SourceDoc doc(String docId, String status, String supersedes) {
        SourceDoc doc = new SourceDoc();
        doc.setDocId(docId);
        doc.setStatus(status);
        doc.setSupersedes(supersedes);
        return doc;
    }

    @Test
    void retiresPagesThatOnlyDependOnSupersededDoc() {
        WikiPage onlyOld = page("运费补贴上限", WikiPageStatus.ACTIVE, "policy-v2024");
        WikiPage mixed = page("运费计价重量", WikiPageStatus.ACTIVE, "policy-v2024", "policy-v2025");
        when(store.listPages(WikiPageStatus.ACTIVE)).thenReturn(List.of(onlyOld, mixed));

        List<RetirementDetector.RetiredPage> retired = detector.retireSupersededBy(
                "policy-v2024", "policy-v2025", Set.of());

        assertThat(retired).extracting(RetirementDetector.RetiredPage::title)
                .containsExactly("运费补贴上限");
        assertThat(retired.get(0).supersededBy()).isEqualTo("policy-v2025");
        assertThat(onlyOld.getStatus()).isEqualTo(WikiPageStatus.RETIRED);
        assertThat(onlyOld.getRetiredAt()).isNotNull();
        // 同时依赖新版的条目还在，不该失效
        assertThat(mixed.getStatus()).isEqualTo(WikiPageStatus.ACTIVE);
        verify(store).updatePage(onlyOld);
        verify(store, never()).updatePage(mixed);
    }

    @Test
    void keepsPagesReestablishedByTheNewVersion() {
        // 新版重新确立了同一个概念：值变了但概念还在，应该更新而不是失效
        WikiPage page = page("包邮门槛", WikiPageStatus.ACTIVE, "policy-v2024");
        when(store.listPages(WikiPageStatus.ACTIVE)).thenReturn(List.of(page));

        List<RetirementDetector.RetiredPage> retired = detector.retireSupersededBy(
                "policy-v2024", "policy-v2025", Set.of(page.getPageId()));

        assertThat(retired).isEmpty();
        assertThat(page.getStatus()).isEqualTo(WikiPageStatus.ACTIVE);
        verify(store, never()).updatePage(page);
    }

    @Test
    void retiresPagesWhoseSourcesAreAllRetired() {
        WikiPage orphan = page("大件配送费", WikiPageStatus.ACTIVE, "policy-bulky-v2023");
        WikiPage alive = page("运费规则", WikiPageStatus.ACTIVE, "policy-freight-v2025");
        when(store.listPages(WikiPageStatus.ACTIVE)).thenReturn(List.of(orphan, alive));

        List<RetirementDetector.RetiredPage> retired = detector.retireOrphaned(List.of(
                doc("policy-bulky-v2023", SourceDoc.STATUS_RETIRED, null),
                doc("policy-freight-v2025", SourceDoc.STATUS_ACTIVE, null)));

        assertThat(retired).extracting(RetirementDetector.RetiredPage::title).containsExactly("大件配送费");
        assertThat(orphan.getStatus()).isEqualTo(WikiPageStatus.RETIRED);
        assertThat(alive.getStatus()).isEqualTo(WikiPageStatus.ACTIVE);
    }

    @Test
    void orphanRetirementPointsToTheDocThatSupersededTheSource() {
        WikiPage orphan = page("大件配送费", WikiPageStatus.ACTIVE, "policy-bulky-v2023");
        when(store.listPages(WikiPageStatus.ACTIVE)).thenReturn(List.of(orphan));

        List<RetirementDetector.RetiredPage> retired = detector.retireOrphaned(List.of(
                doc("policy-bulky-v2023", SourceDoc.STATUS_RETIRED, null),
                doc("policy-bulky-v2025", SourceDoc.STATUS_ACTIVE, "policy-bulky-v2023")));

        assertThat(retired).hasSize(1);
        assertThat(retired.get(0).supersededBy()).isEqualTo("policy-bulky-v2025");
    }

    @Test
    void leavesPartiallyRetiredSourcesAlone() {
        WikiPage page = page("运费补贴上限", WikiPageStatus.ACTIVE,
                "policy-freight-v2024", "policy-member-v2025");
        when(store.listPages(WikiPageStatus.ACTIVE)).thenReturn(List.of(page));

        List<RetirementDetector.RetiredPage> retired = detector.retireOrphaned(List.of(
                doc("policy-freight-v2024", SourceDoc.STATUS_RETIRED, null),
                doc("policy-member-v2025", SourceDoc.STATUS_ACTIVE, null)));

        assertThat(retired).isEmpty();
        assertThat(page.getStatus()).isEqualTo(WikiPageStatus.ACTIVE);
    }
}
