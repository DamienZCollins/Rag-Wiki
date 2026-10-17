package com.ragwiki.agent.api;

/**
 * 评测夹具的复位接口。
 *
 * <p>订单状态是会被工具调用改写的可变夹具：一次 refund 把订单置为 REFUNDING 之后，
 * 后续批次再问同一订单，拿到的状态就与用例标注不符了——这不是模型变差，是夹具漂移。
 * 跑批之前把夹具恢复成基线，批次之间才可比。
 */
public interface EvalFixtureApi {

    /** 把订单夹具恢复成基线，返回实际改动的行数 */
    int restoreOrderFixtures();
}
