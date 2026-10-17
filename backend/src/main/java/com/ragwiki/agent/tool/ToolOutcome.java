package com.ragwiki.agent.tool;

/**
 * 工具执行结果。失败也照常回传给模型，由模型决定怎么跟用户解释。
 */
public record ToolOutcome(boolean success, String content) {

    public static ToolOutcome ok(String content) {
        return new ToolOutcome(true, content);
    }

    public static ToolOutcome error(String content) {
        return new ToolOutcome(false, content);
    }
}
