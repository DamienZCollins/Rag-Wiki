package com.ragwiki.wiki.schema;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 快通道条目：确定性问答对。
 * 派生条目必须能追溯到 Wiki 条目；沉淀条目没有来源，只能停在候选态。
 */
@Getter
@Setter
@TableName("fast_entry")
public class FastEntry {

    public static final String ORIGIN_DERIVED = "DERIVED";
    public static final String ORIGIN_DISTILLED = "DISTILLED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 由规范化后的问题文本哈希得到，重复派生写的是同一个 id，所以是幂等的 */
    private String entryId;

    private String scene;

    private String question;

    private String answer;

    /** JSON 数组，命中判定要求全部命中 */
    private String keywords;

    private String conditions;

    private BigDecimal confidence;

    private FastEntryStatus status;

    private String origin;

    private String sourcePageId;

    private LocalDate effectiveAt;

    private LocalDate expiresAt;

    private Integer hitCount;

    private Instant createdAt;

    private Instant updatedAt;

    public boolean isDerived() {
        return ORIGIN_DERIVED.equals(origin);
    }

    public List<String> keywordList() {
        return parseJsonArray(keywords);
    }

    public static String keywordsJson(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('"').append(values.get(i).replace("\"", "\\\"")).append('"');
        }
        return builder.append(']').toString();
    }

    private static List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        String trimmed = json.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : trimmed.split("\\s*,\\s*")) {
            String value = part.replace("\"", "").strip();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }
}
