package com.ragwiki.wiki.service;

import com.ragwiki.wiki.api.WikiCatalogApi;
import com.ragwiki.wiki.schema.SourceDoc;
import com.ragwiki.wiki.schema.WikiPage;
import com.ragwiki.wiki.schema.WikiPageStatus;
import com.ragwiki.wiki.store.WikiStore;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@link WikiCatalogApi} 的实现：把库里的条目标题与来源文档 id 摊平成集合。
 */
@Service
public class WikiCatalogService implements WikiCatalogApi {

    private final WikiStore store;

    public WikiCatalogService(WikiStore store) {
        this.store = store;
    }

    @Override
    public Set<String> activePageTitles() {
        Set<String> titles = new LinkedHashSet<>();
        for (WikiPage page : store.listPages(WikiPageStatus.ACTIVE)) {
            titles.add(page.getTitle());
        }
        return titles;
    }

    @Override
    public Set<String> sourceDocIds() {
        Set<String> docIds = new LinkedHashSet<>();
        for (SourceDoc doc : store.listSourceDocs()) {
            docIds.add(doc.getDocId());
        }
        return docIds;
    }
}
