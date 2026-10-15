package com.ragwiki.rag.api;

import java.util.List;

/**
 * 索引与别名的控制接口，供索引源对照实验用。
 *
 * <p>在线检索一律走别名，切换索引源就是把别名指到另一个物理索引上。
 * 把这一步做成接口而不是让人手敲 ES 请求，是为了让对照实验能被 runner 完整复现——
 * 实验的可复现性取决于「切换索引源」这个动作也被记录下来、可重放。
 */
public interface IndexControlApi {

    /** 别名当前指向的物理索引 */
    String currentAliasTarget();

    /** 把别名指到指定物理索引上。索引不存在时报错 */
    void switchAlias(String physicalIndex);

    /** 现存的所有 knowledge_* 物理索引名 */
    List<String> physicalIndices();
}
