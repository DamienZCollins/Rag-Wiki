package com.ragwiki.eval.runner;

import com.ragwiki.rag.api.KnowledgeProbeApi;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分层归因。每条用例必须落到唯一一层，且顺序固定：编译 → 召回 → 排序 → 生成 → 兜底。
 */
class AttributorTest {

    private static final List<String> TITLES = List.of("质量问题退货运费补贴上限");
    private static final List<String> DOC_IDS = List.of("policy-freight-rule-v2025");

    private static KnowledgeProbeApi.ProbeResult probe(int expectedRank, int topK, boolean refused) {
        List<KnowledgeProbeApi.ProbeChunk> chunks = new java.util.ArrayList<>();
        int total = Math.max(expectedRank, topK) + 2;
        for (int i = 1; i <= total; i++) {
            chunks.add(new KnowledgeProbeApi.ProbeChunk(
                    "chunk-" + i,
                    i == expectedRank ? "policy-freight-rule-v2025" : "other-doc",
                    null,
                    List.of(),
                    i == expectedRank ? "质量问题退货运费补贴上限" : "无关条目 " + i,
                    "摘要",
                    "正文",
                    10.0,
                    0.8,
                    1.0 / i));
        }
        return new KnowledgeProbeApi.ProbeResult(refused, 12.0, 0.85, 50, topK, List.copyOf(chunks));
    }

    @Test
    void refusalMismatchGoesToFallback() {
        // 该拒答却给了答案
        assertThat(Attributor.attribute(new Attributor.Input(
                true, null, List.of(), List.of(), true, false, false, true, false)))
                .isEqualTo(Attributor.Layer.FALLBACK);
        // 不该拒答却拒了
        assertThat(Attributor.attribute(new Attributor.Input(
                false, probe(1, 8, true), TITLES, DOC_IDS, true, true, false, true, false)))
                .isEqualTo(Attributor.Layer.FALLBACK);
    }

    @Test
    void refusalCasesOnlyJudgeFallback() {
        assertThat(Attributor.attribute(new Attributor.Input(
                true, null, List.of(), List.of(), false, true, false, true, false)))
                .isEqualTo(Attributor.Layer.NONE);
    }

    @Test
    void missingSourceInCatalogGoesToCompile() {
        assertThat(Attributor.attribute(new Attributor.Input(
                false, probe(1, 8, false), TITLES, DOC_IDS, false, false, true, true, false)))
                .isEqualTo(Attributor.Layer.COMPILE);
    }

    @Test
    void sourceNotRecalledGoesToRecall() {
        // 期望来源在候选窗口里一次都没出现
        assertThat(Attributor.attribute(new Attributor.Input(
                false, probe(0, 8, false), TITLES, DOC_IDS, false, false, false, true, false)))
                .isEqualTo(Attributor.Layer.RECALL);
    }

    @Test
    void sourceBeyondTopKGoesToRerank() {
        // 召回到了，但排在第 20 位，超出 topK=8 的生效位次
        assertThat(Attributor.attribute(new Attributor.Input(
                false, probe(20, 8, false), TITLES, DOC_IDS, false, false, false, true, false)))
                .isEqualTo(Attributor.Layer.RERANK);
    }

    @Test
    void sourceWithinTopKButAnswerWrongGoesToGeneration() {
        assertThat(Attributor.attribute(new Attributor.Input(
                false, probe(3, 8, false), TITLES, DOC_IDS, false, false, false, true, false)))
                .isEqualTo(Attributor.Layer.GENERATION);
    }

    @Test
    void allGoodPasses() {
        assertThat(Attributor.attribute(new Attributor.Input(
                false, probe(1, 8, false), TITLES, DOC_IDS, true, false, false, true, false)))
                .isEqualTo(Attributor.Layer.NONE);
    }

    @Test
    void orderCasesWithoutSourceOnlyJudgeGeneration() {
        // 订单用例没有期望来源，检索层不参与判定
        assertThat(Attributor.attribute(new Attributor.Input(
                false, null, List.of(), List.of(), false, false, false, true, false)))
                .isEqualTo(Attributor.Layer.GENERATION);
        assertThat(Attributor.attribute(new Attributor.Input(
                false, null, List.of(), List.of(), true, false, false, true, false)))
                .isEqualTo(Attributor.Layer.NONE);
    }

    @Test
    void answerRightButToolNotCalledGoesToGeneration() {
        // 要点答对了但没查订单，是凭记忆答的：检索层没参与，问题在生成层。
        // 这条曾是归因漏洞——只判答案会把失败用例报成 NONE，等于没归因
        assertThat(Attributor.attribute(new Attributor.Input(
                false, null, List.of(), List.of(), true, false, false, false, false)))
                .isEqualTo(Attributor.Layer.GENERATION);
        // 有知识来源的用例同理：片段给到了却没调检索工具
        assertThat(Attributor.attribute(new Attributor.Input(
                false, probe(1, 8, false), TITLES, DOC_IDS, true, false, false, false, false)))
                .isEqualTo(Attributor.Layer.GENERATION);
    }

    @Test
    void forbiddenPointHitGoesToGeneration() {
        // 要点答对了但顺带给出了被禁的旧口径，也是生成层的问题。
        // 这条曾是归因漏洞——只判要点会把这类失败报成 NONE
        assertThat(Attributor.attribute(new Attributor.Input(
                false, probe(1, 8, false), TITLES, DOC_IDS, true, false, false, true, true)))
                .isEqualTo(Attributor.Layer.GENERATION);
    }
}
