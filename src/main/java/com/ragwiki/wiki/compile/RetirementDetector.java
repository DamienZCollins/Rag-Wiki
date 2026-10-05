package com.ragwiki.wiki.compile;

import com.ragwiki.wiki.schema.SourceDoc;
import com.ragwiki.wiki.schema.WikiPage;
import com.ragwiki.wiki.schema.WikiPageStatus;
import com.ragwiki.wiki.store.WikiStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 失效检测。
 *
 * <p>两条规则：
 * <ol>
 *   <li>新版本取代旧版本时，只依赖被取代版本的条目直接失效，指向取代它的新版本</li>
 *   <li>来源文档全部失效的条目一并失效——概念在新版里被删掉的情况走这条</li>
 * </ol>
 */
@Component
public class RetirementDetector {

    private static final Logger log = LoggerFactory.getLogger(RetirementDetector.class);

    private final WikiStore store;

    public RetirementDetector(WikiStore store) {
        this.store = store;
    }

    public record RetiredPage(String pageId, String title, String supersededBy) {
    }

    /**
     * 规则一：文档 D 取代了 X，把只依赖 X 的条目标记失效。
     *
     * @param reestablishedPageIds 本次编译重新建立起来的条目。新版本如果重新确立了同一个概念，
     *                             该条目应该被更新而不是失效——值是变了，概念还在
     */
    public List<RetiredPage> retireSupersededBy(String supersededDocId, String newDocId,
                                                Set<String> reestablishedPageIds) {
        List<RetiredPage> retired = new ArrayList<>();
        Instant now = Instant.now();

        for (WikiPage page : store.listPages(WikiPageStatus.ACTIVE)) {
            List<String> sources = page.sourceList();
            if (!sources.contains(supersededDocId) || sources.contains(newDocId)) {
                continue;
            }
            if (reestablishedPageIds.contains(page.getPageId())) {
                log.info("条目 {} 被新版本重新确立，保持生效并更新", page.getTitle());
                continue;
            }
            page.retire(newDocId, now);
            store.updatePage(page);
            retired.add(new RetiredPage(page.getPageId(), page.getTitle(), newDocId));
            log.info("条目标记为已失效：{}（被 {} 取代）", page.getTitle(), newDocId);
        }
        return retired;
    }

    /** 规则二：来源文档全部失效的条目一并失效 */
    public List<RetiredPage> retireOrphaned(List<SourceDoc> allDocs) {
        Map<String, SourceDoc> byDocId = allDocs.stream()
                .collect(Collectors.toMap(SourceDoc::getDocId, Function.identity(), (left, right) -> left));

        Set<String> retiredDocIds = byDocId.values().stream()
                .filter(doc -> !doc.isActive())
                .map(SourceDoc::getDocId)
                .collect(Collectors.toCollection(HashSet::new));

        List<RetiredPage> retired = new ArrayList<>();
        Instant now = Instant.now();

        for (WikiPage page : store.listPages(WikiPageStatus.ACTIVE)) {
            List<String> sources = page.sourceList();
            if (sources.isEmpty() || !retiredDocIds.containsAll(sources)) {
                continue;
            }
            String supersededBy = page.getSupersededBy() != null
                    ? page.getSupersededBy()
                    : successorOf(sources, byDocId);
            page.retire(supersededBy, now);
            store.updatePage(page);
            retired.add(new RetiredPage(page.getPageId(), page.getTitle(), supersededBy));
            log.info("条目标记为已失效：{}（来源文档全部失效）", page.getTitle());
        }
        return retired;
    }

    /** 找出取代了这批来源的文档：它 supersedes 其中某一篇 */
    private String successorOf(List<String> sources, Map<String, SourceDoc> byDocId) {
        for (SourceDoc doc : byDocId.values()) {
            if (doc.getSupersedes() != null && sources.contains(doc.getSupersedes())) {
                return doc.getDocId();
            }
        }
        return null;
    }
}
