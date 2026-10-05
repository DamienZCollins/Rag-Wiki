package com.ragwiki.wiki.store;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ragwiki.wiki.mapper.SourceDocMapper;
import com.ragwiki.wiki.mapper.WikiLinkMapper;
import com.ragwiki.wiki.mapper.WikiPageMapper;
import com.ragwiki.wiki.schema.SourceDoc;
import com.ragwiki.wiki.schema.WikiLink;
import com.ragwiki.wiki.schema.WikiPage;
import com.ragwiki.wiki.schema.WikiPageStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 源文档、条目元数据、双链的读写。
 * 条目正文不在这里，走 {@link GitPageStore}。
 */
@Component
public class WikiStore {

    private final SourceDocMapper sourceDocMapper;
    private final WikiPageMapper wikiPageMapper;
    private final WikiLinkMapper wikiLinkMapper;

    public WikiStore(SourceDocMapper sourceDocMapper,
                     WikiPageMapper wikiPageMapper,
                     WikiLinkMapper wikiLinkMapper) {
        this.sourceDocMapper = sourceDocMapper;
        this.wikiPageMapper = wikiPageMapper;
        this.wikiLinkMapper = wikiLinkMapper;
    }

    // ---------- 源文档 ----------

    public void upsertSourceDoc(SourceDoc doc) {
        SourceDoc existing = sourceDocMapper.selectOne(
                Wrappers.<SourceDoc>lambdaQuery().eq(SourceDoc::getDocId, doc.getDocId()));
        Instant now = Instant.now();
        if (existing == null) {
            doc.setCreatedAt(now);
            doc.setUpdatedAt(now);
            sourceDocMapper.insert(doc);
            return;
        }
        doc.setId(existing.getId());
        doc.setCreatedAt(existing.getCreatedAt());
        doc.setUpdatedAt(now);
        sourceDocMapper.updateById(doc);
    }

    public List<SourceDoc> listSourceDocs() {
        return sourceDocMapper.selectList(Wrappers.<SourceDoc>lambdaQuery()
                .orderByAsc(SourceDoc::getDocId));
    }

    public List<SourceDoc> listActiveSourceDocs() {
        return sourceDocMapper.selectList(Wrappers.<SourceDoc>lambdaQuery()
                .eq(SourceDoc::getStatus, SourceDoc.STATUS_ACTIVE)
                .orderByAsc(SourceDoc::getDocId));
    }

    public Optional<SourceDoc> findSourceDoc(String docId) {
        return Optional.ofNullable(sourceDocMapper.selectOne(
                Wrappers.<SourceDoc>lambdaQuery().eq(SourceDoc::getDocId, docId)));
    }

    // ---------- 条目 ----------

    public void insertPage(WikiPage page) {
        wikiPageMapper.insert(page);
    }

    public void updatePage(WikiPage page) {
        wikiPageMapper.updateById(page);
    }

    public Optional<WikiPage> findPage(String pageId) {
        return Optional.ofNullable(wikiPageMapper.selectOne(
                Wrappers.<WikiPage>lambdaQuery().eq(WikiPage::getPageId, pageId)));
    }

    public List<WikiPage> listPages() {
        return wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .orderByAsc(WikiPage::getPageId));
    }

    public List<WikiPage> listPages(WikiPageStatus status) {
        return wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getStatus, status)
                .orderByAsc(WikiPage::getPageId));
    }

    public long countPages(WikiPageStatus status) {
        return wikiPageMapper.selectCount(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getStatus, status));
    }

    // ---------- 双链 ----------

    /** 一条出链的目标。目标条目还不存在时 toPageId 为空，登记为待创建 */
    public record LinkTarget(String toTitle, String toPageId) {
    }

    /** 整体替换某个条目的出链：先删后插，保证与正文里的 [[...]] 一致 */
    public void replaceLinks(String fromPageId, List<LinkTarget> targets) {
        wikiLinkMapper.delete(Wrappers.<WikiLink>lambdaQuery().eq(WikiLink::getFromPageId, fromPageId));

        Instant now = Instant.now();
        for (LinkTarget target : targets) {
            WikiLink link = new WikiLink();
            link.setFromPageId(fromPageId);
            link.setToPageId(target.toPageId());
            link.setToTitle(target.toTitle());
            link.setStatus(target.toPageId() == null ? WikiLink.STATUS_PENDING : WikiLink.STATUS_ACTIVE);
            link.setCreatedAt(now);
            link.setUpdatedAt(now);
            wikiLinkMapper.insert(link);
        }
    }

    /** 某个条目建好之后，把指向它的待创建链接转为已连通 */
    public void resolvePendingLinks(String toPageId, String toTitle) {
        WikiLink update = new WikiLink();
        update.setToPageId(toPageId);
        update.setStatus(WikiLink.STATUS_ACTIVE);
        update.setUpdatedAt(Instant.now());
        wikiLinkMapper.update(update, Wrappers.<WikiLink>lambdaUpdate()
                .eq(WikiLink::getToTitle, toTitle)
                .isNull(WikiLink::getToPageId));
    }

    public List<WikiLink> listLinksFrom(String fromPageId) {
        return wikiLinkMapper.selectList(Wrappers.<WikiLink>lambdaQuery()
                .eq(WikiLink::getFromPageId, fromPageId)
                .orderByAsc(WikiLink::getToTitle));
    }

    public List<WikiLink> listLinksTo(String toPageId) {
        return wikiLinkMapper.selectList(Wrappers.<WikiLink>lambdaQuery()
                .eq(WikiLink::getToPageId, toPageId)
                .orderByAsc(WikiLink::getFromPageId));
    }

    public List<WikiLink> listAllLinks() {
        return wikiLinkMapper.selectList(Wrappers.<WikiLink>lambdaQuery().orderByAsc(WikiLink::getId));
    }

    public void deleteAllLinks() {
        wikiLinkMapper.delete(Wrappers.<WikiLink>lambdaQuery());
    }

    public void deleteAllPages() {
        wikiPageMapper.delete(Wrappers.<WikiPage>lambdaQuery());
    }

    public void deleteAllSourceDocs() {
        sourceDocMapper.delete(Wrappers.<SourceDoc>lambdaQuery());
    }
}
