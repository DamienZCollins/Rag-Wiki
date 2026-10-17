package com.ragwiki.rag.split;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切分策略。纯函数，不需要 Spring，也不需要中间件。
 */
class ChunkSplitterTest {

    private static final ChunkSplitter.Options OPTIONS = new ChunkSplitter.Options(120, 40);

    @Test
    void keepsHeadingPathPerSection() {
        String markdown = """
                # 运费规则

                ## 承担责任划分

                无理由退货由买家承担。

                ## 补贴上限

                质量问题退货最高补 20 元。
                """;

        List<ChunkSplitter.Piece> pieces = ChunkSplitter.split(markdown, OPTIONS);

        assertThat(pieces).hasSize(2);
        assertThat(pieces.get(0).headingPath()).isEqualTo("运费规则 > 承担责任划分");
        assertThat(pieces.get(0).content()).isEqualTo("无理由退货由买家承担。");
        assertThat(pieces.get(1).headingPath()).isEqualTo("运费规则 > 补贴上限");
    }

    @Test
    void neverSplitsTableRows() {
        String markdown = """
                # 规则

                ## 对照表

                | 场景 | 承担方 |
                | --- | --- |
                | 无理由退货 | 买家 |
                | 质量问题 | 平台 |
                | 拒收 | 买家 |
                """;

        List<ChunkSplitter.Piece> pieces = ChunkSplitter.split(markdown, OPTIONS);

        assertThat(pieces).hasSize(1);
        String content = pieces.get(0).content();
        assertThat(content).contains("| 无理由退货 | 买家 |");
        assertThat(content).contains("| 拒收 | 买家 |");
        // 表格整块保留，行数没少
        assertThat(content.lines().filter(line -> line.startsWith("|")).count()).isEqualTo(5);
    }

    @Test
    void splitsLongSectionBySentenceWithOverlap() {
        StringBuilder body = new StringBuilder("# 规则\n\n## 长小节\n\n");
        for (int i = 0; i < 12; i++) {
            body.append("这是第").append(i).append("条规则说明，用来把这一节撑到需要切分。");
        }

        List<ChunkSplitter.Piece> pieces = ChunkSplitter.split(body.toString(), OPTIONS);

        assertThat(pieces).hasSizeGreaterThan(1);
        // 切出来的片段共用同一条标题路径，引用才不会指错小节
        assertThat(pieces).allSatisfy(piece ->
                assertThat(piece.headingPath()).isEqualTo("规则 > 长小节"));
        assertThat(pieces).allSatisfy(piece ->
                assertThat(piece.content().length()).isLessThanOrEqualTo(OPTIONS.maxChars()));
    }

    @Test
    void doesNotCarryOverlapAcrossSections() {
        String markdown = """
                # 规则

                ## 甲小节

                甲小节的第一句话。甲小节的第二句话。

                ## 乙小节

                乙小节的内容。
                """;

        List<ChunkSplitter.Piece> pieces = ChunkSplitter.split(markdown, new ChunkSplitter.Options(40, 30));

        assertThat(pieces).hasSize(2);
        assertThat(pieces.get(1).content()).doesNotContain("甲小节");
    }

    @Test
    void skipsHeadingOnlySections() {
        String markdown = """
                # 规则

                ## 空小节

                ## 有内容的小节

                正文。
                """;

        List<ChunkSplitter.Piece> pieces = ChunkSplitter.split(markdown, OPTIONS);

        assertThat(pieces).hasSize(1);
        assertThat(pieces.get(0).headingPath()).isEqualTo("规则 > 有内容的小节");
    }

    @Test
    void handlesEmptyBody() {
        assertThat(ChunkSplitter.split("", OPTIONS)).isEmpty();
        assertThat(ChunkSplitter.split("   \n\n  ", OPTIONS)).isEmpty();
    }
}
