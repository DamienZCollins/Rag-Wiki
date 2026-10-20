package com.ragwiki.rag.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两个索引源的读取：原文档与 Wiki 条目共用 Markdown 解析，但元数据来源不同。
 */
class IndexReadersTest {

    @TempDir
    Path dir;

    private void write(String fileName, String content) throws Exception {
        Files.writeString(dir.resolve(fileName), content, StandardCharsets.UTF_8);
    }

    @Test
    void corpusReaderKeepsRetiredDocsInIndexForContrast() throws Exception {
        write("policy-a.md", """
                ---
                doc_id: policy-freight-rule-v2024
                title: 运费规则（2024 版）
                category: 运费
                version: v2024.3
                effective_at: 2024-03-01
                status: retired
                ---

                正文
                """);

        List<SourceDocument> documents = new CorpusPolicyReader(dir).read();

        assertThat(documents).hasSize(1);
        SourceDocument document = documents.get(0);
        assertThat(document.docId()).isEqualTo("policy-freight-rule-v2024");
        assertThat(document.pageId()).isNull();
        assertThat(document.sourceDocs()).containsExactly("policy-freight-rule-v2024");
        assertThat(document.status()).isEqualTo("retired");
        assertThat(document.body()).contains("正文");
    }

    @Test
    void wikiReaderUsesPageIdAndAllSourceDocs() throws Exception {
        write("运费补贴上限.md", """
                ---
                page_id: 运费补贴上限
                title: 运费补贴上限
                version: v2025.1
                status: ACTIVE
                effective_at: 2025-01-01
                sources:
                  - policy-freight-rule-v2025
                  - policy-member-benefit
                ---

                ## 摘要

                单笔最高补贴 20 元。

                ## 关联条目

                - [[运费收取与承担规则]]
                """);

        List<SourceDocument> documents = new WikiPageReader(dir).read();

        assertThat(documents).hasSize(1);
        SourceDocument document = documents.get(0);
        assertThat(document.pageId()).isEqualTo("运费补贴上限");
        // 主来源取第一个，完整来源列表保留
        assertThat(document.docId()).isEqualTo("policy-freight-rule-v2025");
        assertThat(document.sourceDocs()).containsExactly(
                "policy-freight-rule-v2025", "policy-member-benefit");
        assertThat(document.status()).isEqualTo("active");
        assertThat(document.title()).isEqualTo("运费补贴上限");
    }

    @Test
    void wikiReaderStripsRelatedSectionBeforeIndexing() {
        String body = """
                ## 摘要

                结论。

                ## 关联条目

                - [[无关条目]]
                """;

        String stripped = WikiPageReader.stripRelatedSection(body);

        assertThat(stripped).contains("结论。");
        assertThat(stripped).doesNotContain("无关条目");
    }

    @Test
    void wikiReaderFallsBackToPageIdWhenSourcesEmpty() throws Exception {
        write("会员权益.md", """
                ---
                page_id: 会员权益
                title: 会员权益
                status: ACTIVE
                ---

                正文
                """);

        SourceDocument document = new WikiPageReader(dir).read().get(0);

        assertThat(document.sourceDocs()).containsExactly("会员权益");
    }

    @Test
    void missingFrontmatterIdIsRejected() throws Exception {
        write("bad.md", """
                ---
                title: 没有 id
                ---

                正文
                """);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CorpusPolicyReader(dir).read())
                .hasMessageContaining("doc_id");
    }
}
