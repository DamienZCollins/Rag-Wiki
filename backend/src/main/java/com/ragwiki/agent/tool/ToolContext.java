package com.ragwiki.agent.tool;

/**
 * 工具执行上下文。有些工具要知道当前是哪一轮会话（比如建单要挂到会话上），
 * 这些信息模型给不了，由内核在调用时注入。
 */
public record ToolContext(String sessionId, Long conversationId) {

    public static ToolContext empty() {
        return new ToolContext(null, null);
    }
}
