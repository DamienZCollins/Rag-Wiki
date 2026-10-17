package com.ragwiki.wiki.api;

/**
 * 快通道对外的接口。agent 只依赖这个包，不碰 wiki 的内部实现。
 */
public interface FastPathApi {

    /**
     * 先试快通道。命中就直答，未命中返回 hit=false 由调用方转 RAG。
     * 全程零模型调用、零向量检索。
     */
    Answer answer(String question);

    /**
     * 记录一次慢通道提问。双通道对比要算省了多少 token、命中率多少，都读这张表。
     *
     * @param latencyMillis 慢通道这一轮的端到端耗时
     */
    void recordSlowPath(String question, long latencyMillis, int tokenIn, int tokenOut, Long conversationId);

    /** 一条快通道回答 */
    record Answer(boolean hit, String entryId, String question, String answer, double confidence, String source) {
        public static Answer miss() {
            return new Answer(false, null, null, null, 0, null);
        }
    }
}
