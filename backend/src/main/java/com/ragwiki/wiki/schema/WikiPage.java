package com.ragwiki.wiki.schema;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wiki 条目元数据。正文以 Markdown 存在 wiki/pages/ 下，库里只有这些字段。
 * 同时充当 MyBatis 实体，不再单独做一层映射对象。
 */
@Getter
@Setter
@TableName("wiki_page")
public class WikiPage {

    /** page_id 里保留不了的字符统一换成连字符，归并时按它匹配 */
    private static final String NORMALIZE_PATTERN = "[\\s《》（）()【】\\[\\]、，,。.：:；;！!？?\"'“”‘’/\\\\|]+";
    /** 结尾的括号组，用来剥掉模型可能会加的版本后缀 */
    private static final Pattern TRAILING_PAREN = Pattern.compile("[（(]([^）)]*)[）)]\\s*$");
    /** 看起来像版本号的内容 */
    private static final Pattern VERSION_LIKE = Pattern.compile("[vV]?\\d+(\\.\\d+)*");
    private static final int PAGE_ID_MAX_LENGTH = 128;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String pageId;

    private String title;

    private String version;

    private WikiPageStatus status;

    private LocalDate effectiveAt;

    private LocalDate expiresAt;

    /** 来源 doc_id 列表，JSON 数组 */
    private String sources;

    /** 输入指纹：来源文档的内容哈希与版本组合，增量编译据此判断要不要重算 */
    private String inputHash;

    private String supersededBy;

    private Instant retiredAt;

    private Instant createdAt;

    private Instant updatedAt;

    public static String pageIdOf(String title) {
        return pageIdOfRaw(normalizeTitle(title));
    }

    /**
     * 剥掉模型可能加在标题末尾的版本后缀，例如「运费规则（v2024.3）（v2025.1）」。
     * 模型很容易把版本号写进标题，一旦写进去，同一个概念每次编译都会变成一个新条目，
     * 归并就失效了。匹配前统一剥掉，让它们落回同一个 page_id。
     */
    public static String normalizeTitle(String title) {
        String cleaned = title.strip();
        while (true) {
            Matcher matcher = TRAILING_PAREN.matcher(cleaned);
            if (!matcher.find()) {
                return cleaned;
            }
            if (!VERSION_LIKE.matcher(matcher.group(1).strip()).matches()) {
                return cleaned;
            }
            cleaned = cleaned.substring(0, matcher.start()).strip();
            if (cleaned.isEmpty()) {
                return title.strip();
            }
        }
    }

    private static String pageIdOfRaw(String title) {
        String normalized = title.trim().toLowerCase()
                .replaceAll(NORMALIZE_PATTERN, "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("条目标题规范化后为空：" + title);
        }
        return normalized.length() <= PAGE_ID_MAX_LENGTH
                ? normalized
                : normalized.substring(0, PAGE_ID_MAX_LENGTH);
    }

    public List<String> sourceList() {
        if (sources == null || sources.isBlank()) {
            return List.of();
        }
        String trimmed = sources.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) {
            return List.of();
        }
        return List.of(trimmed.split("\\s*,\\s*")).stream()
                .map(value -> value.replace("\"", "").strip())
                .filter(value -> !value.isEmpty())
                .toList();
    }

    public static String sourcesJson(List<String> docIds) {
        return docIds.stream()
                .map(docId -> "\"" + docId + "\"")
                .reduce((left, right) -> left + "," + right)
                .map(body -> "[" + body + "]")
                .orElse("[]");
    }

    public void retire(String supersededByDocId, Instant when) {
        status.requireTransitionTo(WikiPageStatus.RETIRED);
        this.status = WikiPageStatus.RETIRED;
        this.supersededBy = supersededByDocId;
        this.retiredAt = when;
        this.updatedAt = when;
    }
}
