package com.ragwiki.wiki.schema;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 源文档元数据。正文不进库，只存内容哈希作为增量编译的输入指纹。
 */
@Getter
@Setter
@TableName("source_doc")
public class SourceDoc {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_RETIRED = "retired";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String docId;

    private String title;

    private String category;

    private String version;

    private LocalDate effectiveAt;

    /** active / retired，与语料 frontmatter 一致，保持小写 */
    private String status;

    private String supersedes;

    private String supersededBy;

    private String contentHash;

    private String path;

    private Instant createdAt;

    private Instant updatedAt;

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }
}
