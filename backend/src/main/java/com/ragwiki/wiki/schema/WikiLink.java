package com.ragwiki.wiki.schema;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 条目双链。目标条目还不存在时 to_page_id 为空、状态为待创建，
 * 这样「指向了还不存在的条目」是登记在案的，不是死链。
 */
@Getter
@Setter
@TableName("wiki_link")
public class WikiLink {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PENDING = "PENDING";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String fromPageId;

    private String toPageId;

    private String toTitle;

    private String status;

    private Instant createdAt;

    private Instant updatedAt;
}
