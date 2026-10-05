package com.ragwiki.common.markdown;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 带 YAML frontmatter 的 Markdown 文档。
 *
 * <p>只支持语料实际用到的子集：标量、空值与字符串列表，不引第三方 YAML 库。
 * 语料格式由项目自己约定，用不着通用解析器。
 */
public record MarkdownDoc(Map<String, Object> frontmatter, String body) {

    private static final String DELIMITER = "---";

    public String text(String key) {
        Object value = frontmatter.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public List<String> list(String key) {
        Object value = frontmatter.get(key);
        if (value instanceof List<?> items) {
            return items.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    public static MarkdownDoc parse(String raw) {
        String normalized = raw.replace("\r\n", "\n");
        if (!normalized.startsWith(DELIMITER + "\n")) {
            return new MarkdownDoc(Map.of(), normalized);
        }

        int end = normalized.indexOf("\n" + DELIMITER + "\n", DELIMITER.length());
        if (end < 0) {
            return new MarkdownDoc(Map.of(), normalized);
        }

        String header = normalized.substring(DELIMITER.length() + 1, end);
        String body = normalized.substring(end + DELIMITER.length() + 2);
        return new MarkdownDoc(parseFrontmatter(header), body);
    }

    private static Map<String, Object> parseFrontmatter(String header) {
        Map<String, Object> data = new LinkedHashMap<>();
        String currentListKey = null;

        for (String rawLine : header.split("\n")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("- ")) {
                if (currentListKey == null) {
                    throw new IllegalArgumentException("frontmatter 列表项没有归属键：" + line);
                }
                @SuppressWarnings("unchecked")
                List<String> items = (List<String>) data.get(currentListKey);
                items.add(line.substring(2).strip());
                continue;
            }

            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("frontmatter 无法解析的行：" + line);
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (value.isEmpty()) {
                // 空值既可能是空标量，也可能是列表的开头，先放一个列表接管
                List<String> items = new ArrayList<>();
                data.put(key, items);
                currentListKey = key;
            } else {
                data.put(key, value);
                currentListKey = null;
            }
        }

        // 没有跟列表项的空值键，回填成空字符串，保持与「字段存在但为空」的语义一致
        data.replaceAll((key, value) -> value instanceof List<?> list && list.isEmpty() ? "" : value);
        return data;
    }
}
