package com.ragwiki.wiki.schema;

/**
 * 快通道条目状态机。候选 → 生效 → 下线，不允许跳变。
 */
public enum FastEntryStatus {

    CANDIDATE,
    ACTIVE,
    OFFLINE;

    public boolean isOnline() {
        return this == ACTIVE;
    }
}
