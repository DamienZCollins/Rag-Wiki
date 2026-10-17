package com.ragwiki.wiki.fastpath;

import com.ragwiki.wiki.schema.FastEntry;
import com.ragwiki.wiki.schema.FastEntryStatus;
import com.ragwiki.wiki.schema.WikiPage;
import com.ragwiki.wiki.schema.WikiPageStatus;
import com.ragwiki.wiki.store.WikiStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 条目上线校验，是上线的强制关卡。
 *
 * <p>校验两件事，都在这一步集中做，不给绕过的入口：
 * <ol>
 *   <li>生效条目：来源 Wiki 条目已经失效的，同步下线——快通道不能继续拿一条作废的政策直答</li>
 *   <li>候选条目：来源必须能追溯到存在的、生效中的 Wiki 条目，且必须有关键词</li>
 * </ol>
 */
@Component
public class EntryPublisher {

    private static final Logger log = LoggerFactory.getLogger(EntryPublisher.class);

    private final WikiStore store;

    public EntryPublisher(WikiStore store) {
        this.store = store;
    }

    public record PublishReport(
            int offlined, List<String> offlinedEntries,
            int published, List<String> publishedEntries,
            int rejected, List<String> rejections) {
    }

    public PublishReport publish() {
        List<String> offlined = offlineEntriesWithRetiredSource();
        List<String> published = new ArrayList<>();
        List<String> rejected = new ArrayList<>();

        for (FastEntry entry : store.listFastEntries(FastEntryStatus.CANDIDATE)) {
            String problem = validate(entry);
            if (problem != null) {
                rejected.add(entry.getQuestion() + "：" + problem);
                log.info("快通道条目未通过上线校验：{}（{}）", entry.getQuestion(), problem);
                continue;
            }
            entry.setStatus(FastEntryStatus.ACTIVE);
            entry.setUpdatedAt(Instant.now());
            store.updateFastEntry(entry);
            published.add(entry.getQuestion());
        }

        log.info("上线校验完成：下线 {}，上线 {}，驳回 {}", offlined.size(), published.size(), rejected.size());
        return new PublishReport(offlined.size(), offlined, published.size(), published, rejected.size(), rejected);
    }

    /** 返回 null 表示校验通过 */
    public String validate(FastEntry entry) {
        if (!StringUtils.hasText(entry.getSourcePageId())) {
            return "没有来源 Wiki 条目，沉淀出来的候选必须人工绑定来源后才能上线";
        }
        Optional<WikiPage> page = store.findPage(entry.getSourcePageId());
        if (page.isEmpty()) {
            return "来源条目不存在：" + entry.getSourcePageId();
        }
        if (page.get().getStatus() != WikiPageStatus.ACTIVE) {
            return "来源条目已失效：" + page.get().getTitle();
        }
        if (entry.keywordList().isEmpty()) {
            return "没有关键词，无法做命中判定";
        }
        if (!StringUtils.hasText(entry.getAnswer())) {
            return "没有答案";
        }
        return null;
    }

    private List<String> offlineEntriesWithRetiredSource() {
        List<String> offlined = new ArrayList<>();
        for (FastEntry entry : store.listFastEntries(FastEntryStatus.ACTIVE)) {
            String sourcePageId = entry.getSourcePageId();
            boolean shouldOffline = !StringUtils.hasText(sourcePageId)
                    || store.findPage(sourcePageId)
                    .map(page -> page.getStatus() != WikiPageStatus.ACTIVE)
                    .orElse(true);
            if (!shouldOffline) {
                continue;
            }
            entry.setStatus(FastEntryStatus.OFFLINE);
            entry.setUpdatedAt(Instant.now());
            store.updateFastEntry(entry);
            offlined.add(entry.getQuestion());
            log.info("快通道条目已下线：{}（来源条目 {} 已失效或不存在）", entry.getQuestion(), sourcePageId);
        }
        return offlined;
    }
}
