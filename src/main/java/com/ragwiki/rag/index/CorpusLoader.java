package com.ragwiki.rag.index;

import com.ragwiki.common.exception.BusinessException;
import com.ragwiki.common.markdown.MarkdownDoc;
import com.ragwiki.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 读取 corpus/policies 下的政策文档。含 _archived/ 里的历史版本，失效状态由 status 字段带出，索引侧按它过滤。
 * 语料只读不可变，这里不做任何写入。
 */
@Component
public class CorpusLoader {

    private static final Logger log = LoggerFactory.getLogger(CorpusLoader.class);

    private final Path policyDir;

    public CorpusLoader(@Value("${ragwiki.corpus.policy-dir:corpus/policies}") String policyDir) {
        this.policyDir = Path.of(policyDir);
    }

    public List<SourceDocument> load() {
        if (!Files.isDirectory(policyDir)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR,
                    "语料目录不存在：" + policyDir.toAbsolutePath());
        }

        List<Path> files;
        try (Stream<Path> paths = Files.walk(policyDir)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("遍历语料目录失败：" + policyDir, e);
        }

        if (files.isEmpty()) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "语料目录下没有 .md 文件：" + policyDir);
        }

        List<SourceDocument> documents = new ArrayList<>(files.size());
        for (Path file : files) {
            documents.add(read(file));
        }
        log.info("读取语料 {} 篇，目录 {}", documents.size(), policyDir.toAbsolutePath());
        return documents;
    }

    private SourceDocument read(Path file) {
        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取语料文件失败：" + file, e);
        }

        MarkdownDoc doc = MarkdownDoc.parse(raw);
        return new SourceDocument(
                required(doc, "doc_id", file),
                required(doc, "title", file),
                doc.text("category"),
                doc.text("version"),
                doc.text("effective_at"),
                required(doc, "status", file),
                doc.body(),
                file);
    }

    private String required(MarkdownDoc doc, String key, Path file) {
        String value = doc.text(key);
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR,
                    "语料文件缺少 frontmatter 字段 %s：%s".formatted(key, file));
        }
        return value;
    }
}
