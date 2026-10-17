package com.ragwiki.rag.index;

import com.ragwiki.common.markdown.MarkdownSource;
import com.ragwiki.common.markdown.MarkdownSourceReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * 索引源：原始政策文档。读 corpus/policies，含 _archived/ 里的历史版本。
 * 失效状态由 status 字段带出，索引侧按它过滤。
 */
public class CorpusPolicyReader implements SourceDocumentReader {

    public static final String SOURCE = "doc";

    private static final Logger log = LoggerFactory.getLogger(CorpusPolicyReader.class);

    private final Path policyDir;

    public CorpusPolicyReader(Path policyDir) {
        this.policyDir = policyDir;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public List<SourceDocument> read() {
        List<MarkdownSource> sources = MarkdownSourceReader.readDirectory(policyDir);
        List<SourceDocument> documents = sources.stream()
                .map(source -> new SourceDocument(
                        source.id(),
                        null,
                        List.of(source.id()),
                        nullToEmpty(source.title()),
                        source.category(),
                        source.version(),
                        source.effectiveAt(),
                        source.status(),
                        source.body(),
                        source.path()))
                .toList();
        log.info("读取语料 {} 篇，目录 {}", documents.size(), policyDir.toAbsolutePath());
        return documents;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
