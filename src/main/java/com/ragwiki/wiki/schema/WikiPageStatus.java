package com.ragwiki.wiki.schema;

import java.util.Map;
import java.util.Set;

/**
 * 条目状态机。只允许 草稿 → 已生效 → 已失效 三条单向边，不允许跳变，也不允许回退。
 */
public enum WikiPageStatus {

    DRAFT,
    ACTIVE,
    RETIRED;

    private static final Map<WikiPageStatus, Set<WikiPageStatus>> ALLOWED = Map.of(
            DRAFT, Set.of(ACTIVE),
            ACTIVE, Set.of(RETIRED),
            RETIRED, Set.of());

    public boolean canTransitionTo(WikiPageStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    public void requireTransitionTo(WikiPageStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("条目状态不允许从 %s 流转到 %s".formatted(this, target));
        }
    }

    public boolean isOnline() {
        return this == ACTIVE;
    }
}
