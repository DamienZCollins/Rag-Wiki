package com.ragwiki.eval.runner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 答案判定口径。这些规则直接决定评测数字，必须可复算。
 */
class AnswerJudgeTest {

    @Test
    void matchesPointWhenAnySynonymAppears() {
        AnswerJudge.PointOutcome outcome = AnswerJudge.judgePoints(
                "质量问题退货的退回运费由平台承担，单笔最高补贴 20 元。",
                List.of(List.of("平台承担", "平台出"), List.of("20元", "20 元")));

        assertThat(outcome.hit()).isEqualTo(2);
        assertThat(outcome.allHit()).isTrue();
    }

    @Test
    void ignoresWhitespaceAndFullWidthWhenMatching() {
        // 模型写「20元」还是「20 元」不该影响判定，全角数字也要能对上
        assertThat(AnswerJudge.judgePoints("最高补贴20元", List.of(List.of("20 元"))).allHit()).isTrue();
        assertThat(AnswerJudge.judgePoints("最高补贴２０元", List.of(List.of("20 元"))).allHit()).isTrue();
    }

    @Test
    void reportsMissedPoints() {
        AnswerJudge.PointOutcome outcome = AnswerJudge.judgePoints(
                "无理由退货由买家承担。",
                List.of(List.of("买家承担"), List.of("平台承担")));

        assertThat(outcome.hit()).isEqualTo(1);
        assertThat(outcome.allHit()).isFalse();
        assertThat(outcome.missed()).containsExactly("平台承担");
    }

    @Test
    void forbidsPointOnlyWhenNotNegated() {
        // 附和旧口径：违规
        assertThat(AnswerJudge.judgeForbidden("质量问题退货最高补 30 元。", List.of("30元")))
                .containsExactly("30元");
        // 纠正旧口径：不违规。「不是 30 元」里的否定词要管住这个匹配
        assertThat(AnswerJudge.judgeForbidden("不是 30 元，现行是 20 元。", List.of("30元")))
                .isEmpty();
        assertThat(AnswerJudge.judgeForbidden("现行口径为 20 元，而非 30 元。", List.of("30元")))
                .isEmpty();
    }

    @Test
    void forbidsWhenNegationBelongsToAnotherClause() {
        // 否定词在上一小句里，不该管到这一句的禁点
        assertThat(AnswerJudge.judgeForbidden("这条不适用；最高补 30 元。", List.of("30元")))
                .containsExactly("30元");
    }

    @Test
    void recognizesRefusalWording() {
        assertThat(AnswerJudge.looksLikeRefusal("这个我查不到相关依据，建议转人工核实。")).isTrue();
        assertThat(AnswerJudge.looksLikeRefusal("这不在我的服务范围内。")).isTrue();
        assertThat(AnswerJudge.looksLikeRefusal("质量问题退货的运费由平台承担。")).isFalse();
    }

    @Test
    void recognizesRefusalWordingFromMeasuredAnswers() {
        // 下面几条都是实测答案里的原话，原先的标记表漏判成了「没拒答」
        assertThat(AnswerJudge.looksLikeRefusal(
                "抱歉，我是电商平台的售后客服，只能处理订单售后、退款、政策咨询这类问题，没法帮您订机票。"))
                .isTrue();
        assertThat(AnswerJudge.looksLikeRefusal(
                "我是电商平台售后客服，只能帮你处理订单、退款、售后政策相关的问题，查不了天气哦。"))
                .isTrue();
        assertThat(AnswerJudge.looksLikeRefusal(
                "餐厅推荐不在我的服务范围内，建议您在大众点评上看看。")).isTrue();
        assertThat(AnswerJudge.looksLikeRefusal(
                "我这边没有平台整体退货率这类统计数据，也没法直接查询。")).isTrue();
        assertThat(AnswerJudge.looksLikeRefusal(
                "我是售后客服，主要负责订单售后、退换货、退款这类问题，暂时没法给您做商品推荐哦。"))
                .isTrue();
        assertThat(AnswerJudge.looksLikeRefusal(
                "我是电商平台的售后客服，只能帮您处理订单、退款、售后政策相关的问题。"))
                .isTrue();
        assertThat(AnswerJudge.looksLikeRefusal(
                "营业执照的办理不在我这边能查的范围内。")).isTrue();
        assertThat(AnswerJudge.looksLikeRefusal(
                "这个我帮不了你，我是电商平台的售后客服，只处理订单、退款、售后政策这类问题。"))
                .isTrue();
    }

    @Test
    void doesNotTreatPolicyAnswerAsRefusal() {
        // 「超出部分要自己出」是正常政策结论，不是拒答。
        // 「超出」曾被收进标记表，会把答对的判成拒答
        assertThat(AnswerJudge.looksLikeRefusal(
                "普通会员单笔最高补贴 20 元，金卡会员最高 30 元，超出部分要自己出。"))
                .isFalse();
        // 自我介绍后照答了越界内容，不算拒答：拒答必须是明确拒绝的说法
        assertThat(AnswerJudge.looksLikeRefusal(
                "世界上最高的山是珠穆朗玛峰，海拔约 8848.86 米。不过我是电商平台的售后客服。"))
                .isFalse();
        // 答了越界内容再补一句「我是售后客服」，仍是越界作答
        assertThat(AnswerJudge.looksLikeRefusal(
                "我是售后客服，拍照对比属于选购参考，我简单聊聊：iPhone 色彩偏真实，华为夜景更强。"))
                .isFalse();
        // 写了用户要的代码，不是拒答
        assertThat(AnswerJudge.looksLikeRefusal(
                "```python def quick_sort(arr): ...```"))
                .isFalse();
    }

    @Test
    void doesNotTreatPolicyMentionOfTransferAsRefusal() {
        // 「转人工」是本领域的政策术语，出现在政策原文里：
        // 「转人工审核」「材料存疑的转人工核查」都是正常作答，不是拒答。
        // 这条曾是判定器 bug——26 条含「转人工」的答案里 21 条被误判成拒答，
        // 直接把通过率与兜底层归因算错
        assertThat(AnswerJudge.looksLikeRefusal(
                "对高频报损设置审核门槛：同一订单 30 天内报损超过 3 次的转人工审核并生成售后工单。"))
                .isFalse();
        assertThat(AnswerJudge.looksLikeRefusal(
                "运费险单笔理赔上限 25 元，免赔额 0 元；材料齐全的，系统在退货签收后 72 小时内自动理赔并到账，"
                        + "材料存疑的转人工核查，核查期最长 5 个工作日。"))
                .isFalse();
        assertThat(AnswerJudge.looksLikeRefusal(
                "金卡及以上会员的咨询优先转人工，排队优先级高于普通会员。"))
                .isFalse();
        // 正题答完之后追加的服务提议，也是正常作答
        assertThat(AnswerJudge.looksLikeRefusal(
                "冷冻牛排需要在签收后 48 小时内报损。如果已经超过 48 小时，平台这边就没办法直接处理了，"
                        + "需要转人工帮你核实看看。"))
                .isFalse();
    }

    @Test
    void recognizesCommittedTransferAsRefusal() {
        // 只认「已经把人转走了」的说法，这是真的把问题推给了人工
        assertThat(AnswerJudge.looksLikeRefusal(
                "已经帮你转人工了，工单号 T20261015134736-9518，客服核实后会回复你。")).isTrue();
        assertThat(AnswerJudge.looksLikeRefusal(
                "抱歉，我这边查不到隐私政策查看路径的相关说明。已经帮你转人工核实了，工单号 T20261015134827-5571。"))
                .isTrue();
    }

    @Test
    void ambiguousGapWordingDoesNotAloneMarkRefusal() {
        // 「没法给」既出现在整体拒答里，也出现在「先答了正题、再补一句没法给准确原因」的正常作答里。
        // 不把它单独当拒答信号，由「没有查到」这类明确的缺失说法兜住
        assertThat(AnswerJudge.looksLikeRefusal(
                "偏远附加费这块，新疆、西藏、内蒙古部分区域要加收，附加费标准以承运商报价为准 [1]。"
                        + "至于为什么要加收，知识库里没有对应的政策说明，我没法给你一个确切依据。"))
                .isFalse();
        // 但整体拒答仍要认得出来
        assertThat(AnswerJudge.looksLikeRefusal(
                "关于会员卡的办理流程，我在售后政策库里没有查到相关规定，没法给你准确的办理方式。")).isTrue();
    }

    @Test
    void recognizesRefusalWithModifierBetweenNegationAndNoun() {
        // 实测 refusal-017：「没有关于客服投诉电话的相关规定」——
        // 「没有」和「相关」之间隔着修饰语，老标记表匹配不上
        assertThat(AnswerJudge.looksLikeRefusal(
                "我查了一下，知识库里没有关于客服投诉电话的相关规定，不能给你准确答复。")).isTrue();
        // 但「没有…规定」这个宽模式不能收：下面这些是正常作答里的缺口说明
        assertThat(AnswerJudge.looksLikeRefusal(
                "包邮门槛按订单实付金额算，规则没有明确说明首重怎么处理，以实际物流计费为准 [1]。"))
                .isFalse();
    }

    @Test
    void answersFirstWhenAllPointsAreHit() {
        // 实测 order-005：订单状态答对了，末尾对答不了的子问题如实说「没查到、建议转人工」。
        // 措辞与整体拒答一模一样，但要点全中，是如实作答不是拒绝
        String answer = "你的订单 SO20250925005 目前状态是「已付款待发货」。"
                + "关于「付款后多久内必须发货」这条时效政策，我在售后知识库里没查到明确规定，"
                + "不能凭猜测给你一个时间，建议转人工客服核实。";

        assertThat(AnswerJudge.looksLikeRefusal(answer)).isTrue();
        assertThat(AnswerJudge.judgedRefusal(answer, true, false)).isFalse();
    }

    @Test
    void stillTreatsGapWordingAsRefusalWhenPointsAreMissing() {
        // 同样的措辞，要点没中就是真拒答
        String answer = "这条政策我在售后知识库里没有查到相关规定，没法给你准确的答案。";

        assertThat(AnswerJudge.judgedRefusal(answer, false, false)).isTrue();
    }

    @Test
    void refusalCasesAreUnaffectedByAnswerFirst() {
        // 拒答用例没有期望要点，答案优先对它不生效：拒了才算过
        String refusal = "这个问题超出了我的服务范围，我是电商平台的售后客服。";
        String answered = "世界上最高的山是珠穆朗玛峰，海拔约 8848.86 米。";

        assertThat(AnswerJudge.judgedRefusal(refusal, false, true)).isTrue();
        assertThat(AnswerJudge.judgedRefusal(answered, false, true)).isFalse();
        // 要点判定的 allHit 对空要点返回 false，空要点用例不会走进答案优先分支
        assertThat(AnswerJudge.judgePoints(refusal, List.of()).allHit()).isFalse();
    }

    @Test
    void checksCitationRange() {
        assertThat(AnswerJudge.citationsInRange("运费由平台承担 [1]，上限 20 元 [3]。", 3)).isTrue();
        // 引用到第 4 条，但只给了 3 条片段
        assertThat(AnswerJudge.citationsInRange("运费由平台承担 [4]。", 3)).isFalse();
        // 非数字的方括号不算引用
        assertThat(AnswerJudge.citationsInRange("见《运费规则》[附注]。", 3)).isTrue();
    }
}
