package com.ragwiki.wiki.compile;

import com.ragwiki.wiki.schema.SourceDoc;
import com.ragwiki.wiki.schema.WikiLink;
import com.ragwiki.wiki.schema.WikiPage;
import com.ragwiki.wiki.schema.WikiPageStatus;
import com.ragwiki.wiki.store.WikiStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 增量编译的规划。
 *
 * <p>只重算输入发生变化的文档（新增，或正文哈希变了）。
 * 另外做一层变化传播：被变化文档产出的条目所指向的条目，其来源文档也算受影响——
 * 上游改了，下游的表述可能就过时了。传播只走一跳，不做闭包，避免一次小改动牵动全库。
 */
@Component
public class IncrementalPlanner {

    private static final Logger log = LoggerFactory.getLogger(IncrementalPlanner.class);

    private final WikiStore store;

    public IncrementalPlanner(WikiStore store) {
        this.store = store;
    }

    public record Plan(List<SourceDoc> toCompile, List<SourceDoc> skipped, List<String> notes) {
    }

    /**
     * @param scanned  本次扫到的全部文档（只读文件，尚未写库）
     * @param previous 库里上一轮记录的源文档。必须在把新哈希写库**之前**取，
     *                 否则比对的永远是刚写进去的自己，永远判成「没变化」
     */
    public Plan plan(List<SourceDoc> scanned, Map<String, SourceDoc> previous) {
        List<SourceDoc> changed = new ArrayList<>();
        List<SourceDoc> skipped = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        for (SourceDoc doc : scanned) {
            SourceDoc before = previous.get(doc.getDocId());
            if (before == null) {
                changed.add(doc);
                notes.add("新增：" + doc.getDocId());
                continue;
            }
            if (!before.getContentHash().equals(doc.getContentHash())) {
                changed.add(doc);
                notes.add("内容变化：" + doc.getDocId());
                continue;
            }
            skipped.add(doc);
        }

        List<SourceDoc> propagated = propagate(changed, skipped);
        if (!propagated.isEmpty()) {
            notes.add("变化传播带出 " + propagated.size() + " 篇：" + propagated.stream()
                    .map(SourceDoc::getDocId).toList());
        }

        List<SourceDoc> toCompile = new ArrayList<>(changed);
        toCompile.addAll(propagated);
        log.info("增量规划：待编译 {} 篇，跳过 {} 篇", toCompile.size(), skipped.size() - propagated.size());
        return new Plan(List.copyOf(toCompile), List.copyOf(skipped), List.copyOf(notes));
    }

    /** 一跳传播：变化文档 → 它产出的条目 → 这些条目的出链目标 → 目标的来源文档 */
    private List<SourceDoc> propagate(List<SourceDoc> changed, List<SourceDoc> skipped) {
        Set<String> changedDocIds = changed.stream().map(SourceDoc::getDocId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (changedDocIds.isEmpty()) {
            return List.of();
        }

        Set<String> affectedPageIds = new LinkedHashSet<>();
        for (WikiPage page : store.listPages()) {
            if (!page.sourceList().stream().anyMatch(changedDocIds::contains)) {
                continue;
            }
            for (WikiLink link : store.listLinksFrom(page.getPageId())) {
                if (link.getToPageId() != null) {
                    affectedPageIds.add(link.getToPageId());
                }
            }
        }
        if (affectedPageIds.isEmpty()) {
            return List.of();
        }

        Set<String> affectedDocIds = new LinkedHashSet<>();
        for (String pageId : affectedPageIds) {
            store.findPage(pageId).ifPresent(page -> affectedDocIds.addAll(page.sourceList()));
        }
        affectedDocIds.removeAll(changedDocIds);

        List<SourceDoc> result = new ArrayList<>();
        for (SourceDoc doc : skipped) {
            if (affectedDocIds.contains(doc.getDocId()) && doc.isActive()) {
                result.add(doc);
            }
        }
        return result;
    }

    /** 条目输入指纹：来源文档的内容哈希与版本按 doc_id 排序拼起来再哈希 */
    public static String inputHash(Map<String, String> docIdToHashAndVersion) {
        String joined = docIdToHashAndVersion.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(Collectors.joining("|"));
        return com.ragwiki.common.markdown.MarkdownSourceReader.sha256(joined);
    }

    public static boolean needsRecompile(WikiPage page, String newHash) {
        return page.getStatus() != WikiPageStatus.ACTIVE || !newHash.equals(page.getInputHash());
    }
}
