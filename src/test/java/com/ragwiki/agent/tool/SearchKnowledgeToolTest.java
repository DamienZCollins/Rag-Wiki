package com.ragwiki.agent.tool;

import com.ragwiki.rag.api.KnowledgeSearchApi;
import com.ragwiki.rag.api.KnowledgeSearchApi.KnowledgeSearchOutcome;
import com.ragwiki.rag.api.KnowledgeSearchApi.KnowledgeSearchOutcome.Citation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * search_knowledge 工具。只依赖 rag 的 api 接口，用桩实现就能测，不需要 ES 也不需要 Spring。
 */
class SearchKnowledgeToolTest {

    private static KnowledgeSearchApi apiReturning(KnowledgeSearchOutcome outcome) {
        return question -> outcome;
    }

    private static Citation citation(String chunkId, String content) {
        return new Citation(chunkId, chunkId.split("#")[0], null, List.of(chunkId.split("#")[0]),
                "运费收取与承担规则", "v2025.1",
                "运费收取与承担规则 > 运费补贴上限", content);
    }

    @Test
    void rendersNumberedCitationsWithTraceableSource() {
        KnowledgeSearchApi api = apiReturning(new KnowledgeSearchOutcome(true, List.of(
                citation("policy-freight-rule-v2025#1", "单笔订单最高补贴 20 元。"),
                citation("policy-member-benefit#2", "金卡及以上会员上限 30 元/单。"))));
        SearchKnowledgeTool tool = new SearchKnowledgeTool(api);

        ToolOutcome outcome = tool.execute(Map.of("question", "运费最多补多少"), ToolContext.empty());

        assertThat(outcome.success()).isTrue();
        String content = outcome.content();
        assertThat(content).contains("[1]");
        assertThat(content).contains("[2]");
        // 编号、文档号、小节路径、片段号都在，前端据此才能把 [1] 还原成具体出处
        assertThat(content).contains("policy-freight-rule-v2025#1");
        assertThat(content).contains("policy-member-benefit#2");
        assertThat(content).contains("运费收取与承担规则 > 运费补贴上限");
        assertThat(content).contains("单笔订单最高补贴 20 元。");
        assertThat(content).contains("只允许引用下面出现过的编号");
    }

    @Test
    void returnsMissPayloadWhenRetrievalRefused() {
        SearchKnowledgeTool tool = new SearchKnowledgeTool(apiReturning(KnowledgeSearchOutcome.miss()));

        ToolOutcome outcome = tool.execute(Map.of("question", "怎么申请营业执照"), ToolContext.empty());

        // 检索没找到依据不算工具执行失败，只是没有结果，由模型决定怎么回应
        assertThat(outcome.success()).isTrue();
        assertThat(outcome.content()).contains("\"hit\":false");
        assertThat(outcome.content()).contains("不要凭常识或猜测作答");
    }

    @Test
    void dropsLeastRelevantChunksWhenOverBudget() {
        List<Citation> citations = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            citations.add(citation("policy-x#" + i, "第 %d 段。".formatted(i) + "内容".repeat(120)));
        }
        SearchKnowledgeTool tool = new SearchKnowledgeTool(apiReturning(
                new KnowledgeSearchOutcome(true, List.copyOf(citations))));

        String content = tool.execute(Map.of("question", "随便问问"), ToolContext.empty()).content();

        assertThat(content).contains("[1]");
        // 融合后排在后面的片段是最不相关的，超预算就从尾部丢
        assertThat(content).doesNotContain("[40]");
    }

    @Test
    void exposesSchemaTheModelCanFollow() {
        SearchKnowledgeTool tool = new SearchKnowledgeTool(apiReturning(KnowledgeSearchOutcome.miss()));

        assertThat(tool.name()).isEqualTo("search_knowledge");
        assertThat(tool.description()).contains("政策");
        assertThat(tool.parameterSchema()).containsEntry("required", List.of("question"));
    }
}
