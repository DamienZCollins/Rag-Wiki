package com.ragwiki.wiki.store;

import com.ragwiki.common.exception.BusinessException;
import com.ragwiki.wiki.schema.PageContent;
import com.ragwiki.wiki.schema.WikiPage;
import com.ragwiki.wiki.schema.WikiPageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 条目正文落盘。一个条目一个 Markdown 文件，元数据在库里，这里只管文件。
 */
class GitPageStoreTest {

    @TempDir
    Path pageDir;

    private GitPageStore store;

    @BeforeEach
    void setUp() {
        // @TempDir 在构造器之后才注入，不能写在字段初始化里
        store = new GitPageStore(pageDir.toString());
    }

    private static WikiPage page(String title) {
        WikiPage page = new WikiPage();
        page.setPageId(WikiPage.pageIdOf(title));
        page.setTitle(title);
        page.setVersion("v2025.1");
        page.setStatus(WikiPageStatus.ACTIVE);
        page.setSources(WikiPage.sourcesJson(List.of("policy-freight-rule-v2025")));
        page.setUpdatedAt(Instant.parse("2026-01-02T03:04:05Z"));
        return page;
    }

    @Test
    void writesOneMarkdownFilePerPage() {
        WikiPage page = page("运费补贴上限");

        store.write(page, new PageContent("单笔最高补贴 20 元。", List.of("普通快递"), List.of(), List.of()));

        assertThat(Files.isRegularFile(pageDir.resolve(page.getPageId() + ".md"))).isTrue();
        assertThat(store.exists(page.getPageId())).isTrue();
    }

    @Test
    void readReturnsParsedContentIncludingChinese() {
        WikiPage page = page("运费补贴上限");
        PageContent content = new PageContent("单笔最高补贴 20 元。", List.of("普通快递"),
                List.of("偏远地区除外"), List.of("运费收取与承担规则"));
        store.write(page, content);

        assertThat(store.read(page.getPageId())).contains(content);
    }

    @Test
    void readReturnsEmptyForMissingPage() {
        assertThat(store.read("不存在")).isEmpty();
    }

    @Test
    void readRawThrowsBusinessErrorForMissingFile() {
        assertThatThrownBy(() -> store.readRaw("不存在"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("不存在"));
    }

    @Test
    void listsPageIdsSorted() {
        store.write(page("运费规则"), PageContent.empty());
        store.write(page("会员权益"), PageContent.empty());

        assertThat(store.listPageIds()).containsExactly("会员权益", "运费规则");
    }

    @Test
    void clearAllRemovesEveryPageFile() {
        store.write(page("运费规则"), PageContent.empty());
        store.write(page("会员权益"), PageContent.empty());

        store.clearAll();

        assertThat(store.listPageIds()).isEmpty();
    }

    @Test
    void missingDirectoryIsTreatedAsEmpty() {
        GitPageStore missing = new GitPageStore(pageDir.resolve("not-created").toString());

        assertThat(missing.listPageIds()).isEmpty();
        assertThat(missing.exists("任意")).isFalse();
    }
}
