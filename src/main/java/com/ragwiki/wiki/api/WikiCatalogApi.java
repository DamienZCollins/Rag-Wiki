package com.ragwiki.wiki.api;

import java.util.Set;

/**
 * Wiki 知识库的目录视图：有哪些条目、哪些来源文档。
 *
 * <p>评测的编译层归因要用它：期望来源在库里根本不存在，属于编译层的问题，
 * 与「存在但没召回」是两回事，只看检索结果分不开。
 */
public interface WikiCatalogApi {

    /** 生效条目的标题 */
    Set<String> activePageTitles();

    /** 已登记的来源文档 doc_id，含已失效的（失效文档仍在索引里作对照） */
    Set<String> sourceDocIds();
}
