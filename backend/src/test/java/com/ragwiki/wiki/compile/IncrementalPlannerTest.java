package com.ragwiki.wiki.compile;

import com.ragwiki.wiki.schema.SourceDoc;
import com.ragwiki.wiki.schema.WikiLink;
import com.ragwiki.wiki.schema.WikiPage;
import com.ragwiki.wiki.schema.WikiPageStatus;
import com.ragwiki.wiki.store.WikiStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 增量规划：只重算输入变化的文档，外加一跳变化传播。
 */
class IncrementalPlannerTest {

    private final WikiStore store = mock(WikiStore.class);
    private final IncrementalPlanner planner = new IncrementalPlanner(store);

    private static SourceDoc doc(String docId, String hash, String status) {
        SourceDoc doc = new SourceDoc();
        doc.setDocId(docId);
        doc.setContentHash(hash);
        doc.setStatus(status);
        return doc;
    }

    private static SourceDoc active(String docId, String hash) {
        return doc(docId, hash, SourceDoc.STATUS_ACTIVE);
    }

    private static WikiPage page(String pageId, WikiPageStatus status, String... sources) {
        WikiPage page = new WikiPage();
        page.setPageId(pageId);
        page.setTitle(pageId);
        page.setStatus(status);
        page.setSources(WikiPage.sourcesJson(List.of(sources)));
        return page;
    }

    private static WikiLink link(String fromPageId, String toPageId) {
        WikiLink link = new WikiLink();
        link.setFromPageId(fromPageId);
        link.setToPageId(toPageId);
        link.setStatus(WikiLink.STATUS_ACTIVE);
        return link;
    }

    @Test
    void marksNewAndChangedDocsForCompile() {
        SourceDoc previous = active("policy-a", "hash-1");
        SourceDoc unchanged = active("policy-b", "hash-2");
        SourceDoc changed = active("policy-c", "hash-3-new");
        SourceDoc added = active("policy-d", "hash-4");

        IncrementalPlanner.Plan plan = planner.plan(
                List.of(unchanged, changed, added),
                Map.of("policy-a", previous, "policy-b", unchanged, "policy-c", active("policy-c", "hash-3-old")));

        assertThat(plan.toCompile()).extracting(SourceDoc::getDocId).containsExactly("policy-c", "policy-d");
        assertThat(plan.skipped()).extracting(SourceDoc::getDocId).containsExactly("policy-b");
        assertThat(plan.notes()).anyMatch(note -> note.contains("新增：policy-d"));
        assertThat(plan.notes()).anyMatch(note -> note.contains("内容变化：policy-c"));
    }

    @Test
    void propagatesOneHopToDownstreamDocs() {
        // a 变了，它产出的条目指向 b 的条目，b 也一并重算
        SourceDoc changed = active("policy-a", "new-hash");
        SourceDoc downstream = active("policy-b", "hash-b");
        SourceDoc unrelated = active("policy-c", "hash-c");

        when(store.listPages()).thenReturn(List.of(
                page("运费规则", WikiPageStatus.ACTIVE, "policy-a"),
                page("运费补贴上限", WikiPageStatus.ACTIVE, "policy-b"),
                page("发票换开", WikiPageStatus.ACTIVE, "policy-c")));
        when(store.listLinksFrom("运费规则")).thenReturn(List.of(link("运费规则", "运费补贴上限")));
        when(store.listLinksFrom("发票换开")).thenReturn(List.of());
        when(store.findPage("运费补贴上限")).thenReturn(java.util.Optional.of(
                page("运费补贴上限", WikiPageStatus.ACTIVE, "policy-b")));

        IncrementalPlanner.Plan plan = planner.plan(
                List.of(changed, downstream, unrelated),
                Map.of("policy-a", active("policy-a", "old-hash"),
                        "policy-b", downstream,
                        "policy-c", unrelated));

        assertThat(plan.toCompile()).extracting(SourceDoc::getDocId)
                .containsExactly("policy-a", "policy-b");
        assertThat(plan.notes()).anyMatch(note -> note.contains("变化传播"));
    }

    @Test
    void doesNotPropagateTwoHops() {
        // 传播只走一跳：b 的条目再指向 c，c 不会被带出来
        SourceDoc changed = active("policy-a", "new-hash");
        SourceDoc downstream = active("policy-b", "hash-b");
        SourceDoc third = active("policy-c", "hash-c");

        when(store.listPages()).thenReturn(List.of(
                page("运费规则", WikiPageStatus.ACTIVE, "policy-a"),
                page("运费补贴上限", WikiPageStatus.ACTIVE, "policy-b"),
                page("会员权益", WikiPageStatus.ACTIVE, "policy-c")));
        when(store.listLinksFrom("运费规则")).thenReturn(List.of(link("运费规则", "运费补贴上限")));
        when(store.listLinksFrom("运费补贴上限")).thenReturn(List.of(link("运费补贴上限", "会员权益")));
        when(store.findPage("运费补贴上限")).thenReturn(java.util.Optional.of(
                page("运费补贴上限", WikiPageStatus.ACTIVE, "policy-b")));

        IncrementalPlanner.Plan plan = planner.plan(
                List.of(changed, downstream, third),
                Map.of("policy-a", active("policy-a", "old-hash"),
                        "policy-b", downstream,
                        "policy-c", third));

        assertThat(plan.toCompile()).extracting(SourceDoc::getDocId)
                .containsExactly("policy-a", "policy-b");
    }

    @Test
    void doesNotPropagateToRetiredDocs() {
        SourceDoc changed = active("policy-a", "new-hash");
        SourceDoc retired = doc("policy-b", "hash-b", SourceDoc.STATUS_RETIRED);

        when(store.listPages()).thenReturn(List.of(page("运费规则", WikiPageStatus.ACTIVE, "policy-a")));
        when(store.listLinksFrom("运费规则")).thenReturn(List.of(link("运费规则", "运费补贴上限")));
        when(store.findPage("运费补贴上限")).thenReturn(java.util.Optional.of(
                page("运费补贴上限", WikiPageStatus.RETIRED, "policy-b")));

        IncrementalPlanner.Plan plan = planner.plan(
                List.of(changed, retired),
                Map.of("policy-a", active("policy-a", "old-hash"), "policy-b", retired));

        assertThat(plan.toCompile()).extracting(SourceDoc::getDocId).containsExactly("policy-a");
    }

    @Test
    void inputHashIsStableRegardlessOfInsertionOrder() {
        String first = IncrementalPlanner.inputHash(Map.of("policy-b", "h2@v2", "policy-a", "h1@v1"));
        String second = IncrementalPlanner.inputHash(Map.of("policy-a", "h1@v1", "policy-b", "h2@v2"));

        assertThat(first).isEqualTo(second).hasSize(64);
        // 任一来源的哈希变了，指纹就要变
        assertThat(IncrementalPlanner.inputHash(Map.of("policy-a", "h1@v1", "policy-b", "h2@v3")))
                .isNotEqualTo(first);
    }

    @Test
    void needsRecompileWhenPageNotActiveOrHashDiffers() {
        WikiPage page = page("运费规则", WikiPageStatus.ACTIVE, "policy-a");
        page.setInputHash("old-hash");

        assertThat(IncrementalPlanner.needsRecompile(page, "old-hash")).isFalse();
        assertThat(IncrementalPlanner.needsRecompile(page, "new-hash")).isTrue();

        page.setStatus(WikiPageStatus.RETIRED);
        assertThat(IncrementalPlanner.needsRecompile(page, "old-hash")).isTrue();
    }
}
