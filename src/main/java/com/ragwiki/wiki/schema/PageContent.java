package com.ragwiki.wiki.schema;

import com.ragwiki.common.markdown.MarkdownDoc;

import java.util.ArrayList;
import java.util.List;

/**
 * 条目正文的结构。正文里只允许这四段：摘要、适用条件、例外、关联条目。
 * 结构固定才好在 Git 里 diff，也才能让 lint 按段定位问题。
 */
public record PageContent(
        String summary,
        List<String> conditions,
        List<String> exceptions,
        List<String> relatedTitles) {

    public static final String SECTION_SUMMARY = "摘要";
    public static final String SECTION_CONDITIONS = "适用条件";
    public static final String SECTION_EXCEPTIONS = "例外";
    public static final String SECTION_RELATED = "关联条目";

    private static final String HEADING_PREFIX = "## ";
    private static final String ITEM_PREFIX = "- ";
    private static final String LINK_PREFIX = "[[";
    private static final String LINK_SUFFIX = "]]";
    private static final String EMPTY_MARK = "无";

    public static PageContent empty() {
        return new PageContent("", List.of(), List.of(), List.of());
    }

    /** 渲染成 Markdown。frontmatter 只放让文件自描述的最小集，权威元数据在库里 */
    public String render(WikiPage page) {
        StringBuilder builder = new StringBuilder();
        builder.append("---\n");
        builder.append("page_id: ").append(page.getPageId()).append('\n');
        builder.append("title: ").append(page.getTitle()).append('\n');
        if (page.getVersion() != null) {
            builder.append("version: ").append(page.getVersion()).append('\n');
        }
        builder.append("status: ").append(page.getStatus()).append('\n');
        if (page.getEffectiveAt() != null) {
            builder.append("effective_at: ").append(page.getEffectiveAt()).append('\n');
        }
        builder.append("sources:\n");
        for (String source : page.sourceList()) {
            builder.append("  - ").append(source).append('\n');
        }
        builder.append("updated_at: ").append(page.getUpdatedAt()).append('\n');
        builder.append("---\n\n");

        builder.append(HEADING_PREFIX).append(SECTION_SUMMARY).append("\n\n");
        builder.append(summary == null ? "" : summary.strip()).append("\n\n");

        appendList(builder, SECTION_CONDITIONS, conditions);
        appendList(builder, SECTION_EXCEPTIONS, exceptions);
        appendList(builder, SECTION_RELATED, relatedTitles.stream()
                .map(title -> LINK_PREFIX + title + LINK_SUFFIX)
                .toList());
        return builder.toString();
    }

    private static void appendList(StringBuilder builder, String heading, List<String> items) {
        builder.append(HEADING_PREFIX).append(heading).append("\n\n");
        if (items == null || items.isEmpty()) {
            builder.append(ITEM_PREFIX).append(EMPTY_MARK).append("\n\n");
            return;
        }
        for (String item : items) {
            builder.append(ITEM_PREFIX).append(item.strip()).append('\n');
        }
        builder.append('\n');
    }

    /** 解析正文。只认固定的四段，其它段落忽略 */
    public static PageContent parse(String markdown) {
        String body = MarkdownDoc.parse(markdown).body();
        List<String> summaryLines = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();
        List<String> related = new ArrayList<>();

        String section = null;
        for (String rawLine : body.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.startsWith(HEADING_PREFIX)) {
                section = line.substring(HEADING_PREFIX.length()).strip();
                continue;
            }
            if (section == null || line.isEmpty()) {
                continue;
            }
            switch (section) {
                case SECTION_SUMMARY -> summaryLines.add(line);
                case SECTION_CONDITIONS -> addItem(conditions, line);
                case SECTION_EXCEPTIONS -> addItem(exceptions, line);
                case SECTION_RELATED -> addRelated(related, line);
                default -> {
                    // 其余段落忽略
                }
            }
        }
        return new PageContent(
                String.join("\n", summaryLines),
                List.copyOf(conditions),
                List.copyOf(exceptions),
                List.copyOf(related));
    }

    private static void addItem(List<String> target, String line) {
        String item = line.startsWith(ITEM_PREFIX) ? line.substring(ITEM_PREFIX.length()).strip() : line;
        if (!item.isEmpty() && !EMPTY_MARK.equals(item)) {
            target.add(item);
        }
    }

    private static void addRelated(List<String> target, String line) {
        String item = line.startsWith(ITEM_PREFIX) ? line.substring(ITEM_PREFIX.length()).strip() : line;
        if (item.startsWith(LINK_PREFIX) && item.endsWith(LINK_SUFFIX)) {
            item = item.substring(LINK_PREFIX.length(), item.length() - LINK_SUFFIX.length()).strip();
        }
        if (!item.isEmpty() && !EMPTY_MARK.equals(item)) {
            target.add(item);
        }
    }
}
