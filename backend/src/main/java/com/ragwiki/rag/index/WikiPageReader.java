package com.ragwiki.rag.index;

import com.ragwiki.common.markdown.MarkdownSource;
import com.ragwiki.common.markdown.MarkdownSourceReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * 索引源：Wiki 页面。读 wiki/pages 下的 Markdown 条目。
 *
 * <p>这是 wiki 与 rag 之间的文件契约：rag 按路径读文件，不引用 wiki 的类。
 */
public class WikiPageReader implements SourceDocumentReader {

    public static final String SOURCE = "wiki";

    private static final Logger log = LoggerFactory.getLogger(WikiPageReader.class);
    private static final String HEADING_PREFIX = "## ";
    private static final String RELATED_SECTION = "关联条目";

    private final Path pageDir;

    public WikiPageReader(Path pageDir) {
        this.pageDir = pageDir;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public List<SourceDocument> read() {
        List<MarkdownSource> pages = MarkdownSourceReader.readDirectory(pageDir, "page_id");
        List<SourceDocument> documents = pages.stream()
                .map(page -> new SourceDocument(
                        primarySourceDoc(page),
                        page.id(),
                        page.sources().isEmpty() ? List.of(page.id()) : page.sources(),
                        page.title() == null ? page.id() : page.title(),
                        null,
                        page.version(),
                        page.effectiveAt(),
                        page.status() == null ? null : page.status().toLowerCase(),
                        stripRelatedSection(page.body()),
                        page.path()))
                .toList();
        log.info("读取 Wiki 条目 {} 个，目录 {}", documents.size(), pageDir.toAbsolutePath());
        return documents;
    }

    /**
     * 去掉「关联条目」段再进索引。
     * 那一段只有 [[链接]]，是导航不是内容：留着会让 BM25 靠条目标题匹配上无关片段，
     * 也会白白多出一批没有信息量的片段。链接关系在 wiki_link 与文件 frontmatter 里都有。
     */
    static String stripRelatedSection(String body) {
        String[] lines = body.split("\n", -1);
        StringBuilder kept = new StringBuilder();
        boolean skipping = false;
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith(HEADING_PREFIX)) {
                skipping = trimmed.substring(HEADING_PREFIX.length()).strip().equals(RELATED_SECTION);
            }
            if (!skipping) {
                kept.append(line).append('\n');
            }
        }
        return kept.toString();
    }

    /** 条目可能归并了多个来源文档，索引里只带主来源，完整列表在条目的 frontmatter 里 */
    private static String primarySourceDoc(MarkdownSource page) {
        return page.sources().isEmpty() ? page.id() : page.sources().get(0);
    }
}
