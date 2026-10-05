package com.ragwiki.agent.tool;

import com.ragwiki.rag.api.KnowledgeSearchApi;
import com.ragwiki.rag.api.KnowledgeSearchApi.KnowledgeSearchOutcome;
import com.ragwiki.rag.api.KnowledgeSearchApi.KnowledgeSearchOutcome.Citation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 检索知识库。
 * 只依赖 rag 的 api 包，不碰 rag 的内部实现。
 *
 * <p>检索结果拼成带编号的文本给模型：编号既是模型写引用标记的依据，
 * 也是前端把 [1] 还原成具体来源的依据——片段号、文档号、小节路径都在里面。
 */
@Component
public class SearchKnowledgeTool implements Tool {

    /** 拼给模型的上下文预算，超了就按相关性从尾部丢，尾部是融合后最不相关的 */
    private static final int CONTEXT_BUDGET_CHARS = 6000;

    private static final String MISS_PAYLOAD = """
            {"hit":false,"message":"知识库里没有找到与这个问题相关的政策依据。\
            不要凭常识或猜测作答，如实告诉用户你查不到这条依据，并建议转人工核实。"}""";

    private final KnowledgeSearchApi knowledgeSearchApi;

    public SearchKnowledgeTool(KnowledgeSearchApi knowledgeSearchApi) {
        this.knowledgeSearchApi = knowledgeSearchApi;
    }

    @Override
    public String name() {
        return "search_knowledge";
    }

    @Override
    public String description() {
        return "检索售后政策知识库，返回与问题相关的政策原文片段与来源。"
                + "用户问到售后规则、时效、费用标准、金额上限、处理流程这类问题时调用；"
                + "政策类问题必须先查再答，不要凭记忆回答。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "question", Map.of(
                                "type", "string",
                                "description", "要检索的问题，用用户的原话或改写成一句完整的问句")),
                "required", List.of("question"));
    }

    @Override
    public ToolOutcome execute(Map<String, Object> arguments, ToolContext context) {
        String question = String.valueOf(arguments.get("question")).trim();
        KnowledgeSearchOutcome outcome = knowledgeSearchApi.search(question);

        if (!outcome.hit()) {
            return ToolOutcome.ok(MISS_PAYLOAD);
        }
        return ToolOutcome.ok(render(outcome));
    }

    private String render(KnowledgeSearchOutcome outcome) {
        StringBuilder builder = new StringBuilder(
                "以下是知识库中与该问题相关的政策片段。在结论后用 [编号] 标注依据，只允许引用下面出现过的编号。\n");

        int used = 0;
        int number = 1;
        for (Citation citation : outcome.citations()) {
            String block = renderCitation(number, citation);
            if (number > 1 && used + block.length() > CONTEXT_BUDGET_CHARS) {
                break;
            }
            builder.append('\n').append(block);
            used += block.length();
            number++;
        }
        return builder.toString();
    }

    private String renderCitation(int number, Citation citation) {
        return """
                [%d] 《%s》
                    小节：%s
                    来源：%s（%s）
                    片段：%s
                    正文：%s""".formatted(
                number,
                citation.title(),
                citation.headingPath(),
                citation.docId(),
                citation.version(),
                citation.chunkId(),
                citation.content());
    }
}
