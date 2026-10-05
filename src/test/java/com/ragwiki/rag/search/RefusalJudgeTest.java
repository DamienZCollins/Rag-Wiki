package com.ragwiki.rag.search;

import com.ragwiki.rag.RagProperties;
import com.ragwiki.rag.dto.ChunkHit;
import com.ragwiki.rag.dto.ChunkMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拒答规则：两路最高原始分**都**低于各自阈值才拒答。
 * 这是「单路分」口径的核心——任一路足够高就说明知识库里有东西，不该拒。
 */
class RefusalJudgeTest {

    private static final double BM25_THRESHOLD = 8.0;
    private static final double VECTOR_THRESHOLD = 0.72;

    private final RefusalJudge judge = new RefusalJudge(new RagProperties(
            null, null, null, 50, 8,
            new RagProperties.Refuse(BM25_THRESHOLD, VECTOR_THRESHOLD)));

    @Test
    void refusesWhenBothPathsAreBelowThreshold() {
        assertThat(judge.shouldRefuse(hits(3.0), hits(0.65))).isTrue();
    }

    @Test
    void refusesWhenBothPathsReturnNothing() {
        assertThat(judge.shouldRefuse(List.of(), List.of())).isTrue();
    }

    @Test
    void hitsWhenOnlyKeywordPathIsStrong() {
        // 关键词问法命中充分，口语化程度低时 BM25 更可靠
        assertThat(judge.shouldRefuse(hits(15.0), hits(0.60))).isFalse();
    }

    @Test
    void hitsWhenOnlyVectorPathIsStrong() {
        // 口语化问法 BM25 会很低，靠向量兜住——这正是双路存在的理由
        assertThat(judge.shouldRefuse(hits(2.5), hits(0.83))).isFalse();
    }

    @Test
    void hitsWhenBothPathsAreStrong() {
        assertThat(judge.shouldRefuse(hits(12.0), hits(0.88))).isFalse();
    }

    @Test
    void topScoreIsZeroWhenPathReturnsNothing() {
        assertThat(RefusalJudge.topScore(List.of())).isZero();
        assertThat(RefusalJudge.topScore(hits(4.2))).isEqualTo(4.2);
    }

    private static List<ChunkHit> hits(double score) {
        return List.of(new ChunkHit(new ChunkMeta("c#0", "doc", "标题", "小节", "正文", "类目", "v1"), score, 1));
    }
}
