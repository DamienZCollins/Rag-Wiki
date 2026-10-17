package com.ragwiki.wiki.compile;

import java.util.List;

/**
 * 编译模型抽出来的候选条目。mergeInto 非空表示模型判断它与已有条目是同一个概念。
 */
public record ExtractedEntry(
        String title,
        String mergeInto,
        String summary,
        List<String> conditions,
        List<String> exceptions,
        List<String> related) {

    public ExtractedEntry {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
        related = related == null ? List.of() : List.copyOf(related);
    }
}
