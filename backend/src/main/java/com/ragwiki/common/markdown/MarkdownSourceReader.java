package com.ragwiki.common.markdown;

import com.ragwiki.common.exception.BusinessException;
import com.ragwiki.common.result.ResultCode;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * 递归读取一个目录下的 Markdown 源文档，解析 frontmatter 并算正文哈希。
 * 只读，不改动任何文件。
 */
public final class MarkdownSourceReader {

    /** frontmatter 里的 id 字段名，语料用 doc_id，Wiki 条目用 page_id */
    private static final String DEFAULT_ID_FIELD = "doc_id";

    private MarkdownSourceReader() {
    }

    public static List<MarkdownSource> readDirectory(Path directory) {
        return readDirectory(directory, DEFAULT_ID_FIELD);
    }

    public static List<MarkdownSource> readDirectory(Path directory, String idField) {
        if (!Files.isDirectory(directory)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR,
                    "目录不存在：" + directory.toAbsolutePath());
        }

        List<Path> files;
        try (Stream<Path> paths = Files.walk(directory)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("遍历目录失败：" + directory, e);
        }

        List<MarkdownSource> sources = new ArrayList<>(files.size());
        for (Path file : files) {
            sources.add(read(file, idField));
        }
        return sources;
    }

    public static MarkdownSource read(Path file, String idField) {
        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件失败：" + file, e);
        }

        MarkdownDoc doc = MarkdownDoc.parse(raw);
        String id = doc.text(idField);
        if (!StringUtils.hasText(id) && !DEFAULT_ID_FIELD.equals(idField)) {
            id = doc.text(DEFAULT_ID_FIELD);
        }
        if (!StringUtils.hasText(id)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR,
                    "文件缺少 frontmatter 字段 %s：%s".formatted(idField, file));
        }

        return new MarkdownSource(
                id,
                doc.text("title"),
                doc.text("category"),
                doc.text("version"),
                doc.text("effective_at"),
                doc.text("status"),
                doc.text("supersedes"),
                doc.text("superseded_by"),
                doc.list("sources"),
                sha256(doc.body()),
                doc.body(),
                file);
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
