package com.ragwiki.agent.tool;

import java.util.Map;

/**
 * Agent 可调用的工具。
 * 参数 schema 用 JSON Schema 描述，直接交给模型；参数校验与异常兜底由 {@link ToolRegistry} 统一处理。
 */
public interface Tool {

    /** 工具名，模型按这个名字调用 */
    String name();

    /** 工具用途说明，模型据此判断何时调用 */
    String description();

    /** JSON Schema 形式的参数定义 */
    Map<String, Object> parameterSchema();

    /**
     * 执行工具。
     * 参数已按 schema 校验过；业务上的失败通过 {@link ToolOutcome#error} 返回，不要抛异常。
     *
     * @param context 本轮会话上下文，模型给不了的信息由内核在这里补
     */
    ToolOutcome execute(Map<String, Object> arguments, ToolContext context) throws Exception;
}
