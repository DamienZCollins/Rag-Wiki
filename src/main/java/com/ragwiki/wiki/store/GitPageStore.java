package com.ragwiki.wiki.store;

import com.ragwiki.common.exception.BusinessException;
import com.ragwiki.common.result.ResultCode;
import com.ragwiki.wiki.schema.PageContent;
import com.ragwiki.wiki.schema.WikiPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 条目正文的落盘读写。正文以 Markdown 存 wiki/pages/ 下，元数据在库里，
 * 所以这里只管文件，不碰数据库。
 *
 * <p>刻意不做自动 git 提交：编译产物该由人看过 diff 之后再提交，
 * 应用在后台改仓库状态是意外副作用。「版本可追溯」靠的是文件进了版本库，不是应用代替人提交。
 */
@Component
public class GitPageStore {

    private static final Logger log = LoggerFactory.getLogger(GitPageStore.class);
    private static final String SUFFIX = ".md";

    private final Path pageDir;

    public GitPageStore(@Value("${wiki.page-dir:wiki/pages}") String pageDir) {
        this.pageDir = Path.of(pageDir);
    }

    public Path directory() {
        return pageDir;
    }

    public Path pathOf(String pageId) {
        return pageDir.resolve(pageId + SUFFIX);
    }

    public boolean exists(String pageId) {
        return Files.isRegularFile(pathOf(pageId));
    }

    public void write(WikiPage page, PageContent content) {
        Path file = pathOf(page.getPageId());
        try {
            Files.createDirectories(pageDir);
            Files.writeString(file, content.render(page), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入条目失败：" + file, e);
        }
    }

    public String readRaw(String pageId) {
        Path file = pathOf(pageId);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.NOT_FOUND, "条目文件不存在：" + file);
        }
    }

    public Optional<PageContent> read(String pageId) {
        if (!exists(pageId)) {
            return Optional.empty();
        }
        return Optional.of(PageContent.parse(readRaw(pageId)));
    }

    public List<String> listPageIds() {
        if (!Files.isDirectory(pageDir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(pageDir)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(SUFFIX))
                    .map(name -> name.substring(0, name.length() - SUFFIX.length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("列出条目目录失败：" + pageDir, e);
        }
    }

    public void clearAll() {
        List<String> pageIds = listPageIds();
        for (String pageId : pageIds) {
            try {
                Files.deleteIfExists(pathOf(pageId));
            } catch (IOException e) {
                throw new UncheckedIOException("删除条目失败：" + pathOf(pageId), e);
            }
        }
        log.info("已清空条目目录，删除 {} 个文件", pageIds.size());
    }
}
