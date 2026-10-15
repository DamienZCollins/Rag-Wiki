package com.ragwiki.agent.api;

import java.util.List;

/**
 * 会话内核对外的接口。eval 只依赖这个包，不碰 agent 的内部实现。
 *
 * <p>一次调用推进一轮，与 HTTP 入口走的是同一条内核路径，差别只在事件出口：
 * 这里把事件收在内存里返回结构化结果，HTTP 那边推 SSE。
 */
public interface AgentChatApi {

    /**
     * 跑一轮对话，返回本轮的结果。
     * 会话不存在时按新会话处理，与 HTTP 入口一致。
     */
    TurnResult runTurn(String sessionId, String message);

    /** 一轮对话的结果 */
    record TurnResult(
            /** 模型输出或快通道直答拼起来的完整文本 */
            String answer,
            /** 本轮发生的工具调用，按发生顺序 */
            List<ToolInvocation> toolInvocations,
            /** 本轮消耗的输入 token，快通道为 0 */
            int tokenIn,
            /** 本轮消耗的输出 token，快通道为 0 */
            int tokenOut,
            /** 终止原因：COMPLETED / MAX_STEPS / FAILED */
            String finishReason,
            /** 是否走了快通道。快通道不调用模型，事件流里不会有 THINKING */
            boolean fastPathHit,
            /** 非空表示本轮出错，取值为错误信息 */
            String error) {

        public boolean completed() {
            return "COMPLETED".equals(finishReason);
        }
    }

    /** 一次工具调用及其结果 */
    record ToolInvocation(String name, String arguments, boolean success, String result) {
    }
}
