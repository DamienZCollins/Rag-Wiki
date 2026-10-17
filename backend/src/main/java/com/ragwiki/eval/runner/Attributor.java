package com.ragwiki.eval.runner;

import com.ragwiki.rag.api.KnowledgeProbeApi;

import java.util.List;

/**
 * 分层归因：把一条失败用例归到唯一一层。
 *
 * <p>归因顺序固定：编译 → 召回 → 排序 → 生成 → 兜底。命中第一个失败层就停，不往下判——
 * 一条用例失败常常是多层连锁的（没召回自然也谈不上用好），只报最上游的那一层，
 * 才对应「该去改哪里」。
 *
 * <p>召回与排序用检索探针判，而不是看模型调没调工具：模型调没调工具是生成层的行为，
 * 拿它判召回会把两层的账算混。探针走的是与在线问答同一条检索路径。
 */
public final class Attributor {

    /** 归因层。NONE 表示通过，不归因 */
    public enum Layer {
        /** 编译层：Wiki 条目本身缺失或不对 */
        COMPILE,
        /** 召回层：期望片段没进候选窗口 */
        RECALL,
        /** 排序层：召回到了但排在生效位次之外 */
        RERANK,
        /** 生成层：片段给到了，答案没用对或没用上 */
        GENERATION,
        /** 兜底层：该拒答的没拒、不该拒的拒了 */
        FALLBACK,
        /** 通过 */
        NONE
    }

    private Attributor() {
    }

    /**
     * 一条用例的判定输入。字段够归因用即可，不把整条用例塞进来。
     *
     * @param expectRefusal  用例是否期望拒答
     * @param probe          检索探针结果，拒答用例可能为 null（没走到检索）
     * @param expectedTitles 期望命中的 Wiki 条目标题
     * @param expectedDocIds 期望命中的来源文档
     * @param answerPassed   答案要点判定是否通过
     * @param actualRefused  链路实际是否拒答
     * @param sourceMissing  期望来源在 Wiki 库里就不存在（编译层问题）
     * @param toolOk         期望的工具是否被调用、参数是否正确
     * @param forbiddenHit   答案给出了用例明确禁止的旧口径
     */
    public record Input(
            boolean expectRefusal,
            KnowledgeProbeApi.ProbeResult probe,
            List<String> expectedTitles,
            List<String> expectedDocIds,
            boolean answerPassed,
            boolean actualRefused,
            boolean sourceMissing,
            boolean toolOk,
            boolean forbiddenHit) {
    }

    /**
     * 判定归因层。返回 NONE 表示这一层链路是通的（不代表整条用例通过，
     * 用例通过与否由 {@link EvalRunner} 综合各层判定）。
     */
    public static Layer attribute(Input input) {
        // 兜底层：该拒的没拒、不该拒的拒了，都在这一层
        if (input.expectRefusal() != input.actualRefused()) {
            return Layer.FALLBACK;
        }
        // 拒答用例只判兜底，答对了就通过，不要求召回
        if (input.expectRefusal()) {
            return Layer.NONE;
        }

        // 编译层：期望来源在 Wiki 库里根本不存在，再怎么检索也召不回来
        if (input.sourceMissing()) {
            return Layer.COMPILE;
        }

        List<String> titles = input.expectedTitles();
        List<String> docIds = input.expectedDocIds();
        if (titles.isEmpty() && docIds.isEmpty()) {
            // 订单类用例没有期望知识来源，检索层不参与判定：
            // 它没有探针结果，不能据此报召回层，只看工具与答案对不对
            return generationOrNone(input);
        }

        KnowledgeProbeApi.ProbeResult probe = input.probe();
        if (probe == null) {
            // 有期望来源却没拿到探针结果，说明检索没跑成，按召回层报
            return Layer.RECALL;
        }

        // 召回了但被拒答：两路原始分低于阈值，属于兜底判定把有依据的问题挡了
        if (probe.refused()) {
            return Layer.FALLBACK;
        }

        int bestRank = probe.bestRank(titles, docIds);
        if (bestRank == 0) {
            return Layer.RECALL;
        }
        if (bestRank > probe.topK()) {
            return Layer.RERANK;
        }
        // 片段给到了，答案或工具没用对，都算生成层：该调工具却没调，是模型这一侧的问题
        return generationOrNone(input);
    }

    /** 检索层没问题时的判定：答案、工具、禁点三者有一个不对就是生成层 */
    private static Layer generationOrNone(Input input) {
        if (!input.answerPassed() || !input.toolOk() || input.forbiddenHit()) {
            return Layer.GENERATION;
        }
        return Layer.NONE;
    }

    /** 归因层的中文名，报告里用 */
    public static String label(Layer layer) {
        return switch (layer) {
            case COMPILE -> "编译层";
            case RECALL -> "召回层";
            case RERANK -> "排序层";
            case GENERATION -> "生成层";
            case FALLBACK -> "兜底层";
            case NONE -> "通过";
        };
    }
}
