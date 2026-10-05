package com.ragwiki.wiki.compile;

import com.ragwiki.wiki.schema.PageContent;
import com.ragwiki.wiki.schema.WikiPage;
import com.ragwiki.wiki.store.WikiStore;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 双链生成。条目正文里的关联标题写成 [[标题]]，同时落成 wiki_link 记录。
 *
 * <p>指向还不存在的条目不是死链：to_page_id 留空、状态记为待创建，
 * 等目标条目建出来时再转正。这样 lint 能把「待创建」和「指向了错误目标」区分开。
 */
@Component
public class LinkBuilder {

    private final WikiStore store;

    public LinkBuilder(WikiStore store) {
        this.store = store;
    }

    /** 按正文里的关联标题整体重建出链 */
    public void rebuild(WikiPage page, PageContent content) {
        Map<String, WikiStore.LinkTarget> targets = new LinkedHashMap<>();
        for (String rawTitle : content.relatedTitles()) {
            String title = WikiPage.normalizeTitle(rawTitle);
            if (title.isEmpty() || title.equals(page.getTitle())) {
                continue;
            }
            String targetId = WikiPage.pageIdOf(title);
            boolean exists = store.findPage(targetId).isPresent();
            targets.put(title, new WikiStore.LinkTarget(title, exists ? targetId : null));
        }
        store.replaceLinks(page.getPageId(), List.copyOf(targets.values()));
    }

    /** 某条目刚建出来，把指向它的待创建链接转成已连通 */
    public void resolveIncoming(WikiPage page) {
        store.resolvePendingLinks(page.getPageId(), page.getTitle());
    }
}
