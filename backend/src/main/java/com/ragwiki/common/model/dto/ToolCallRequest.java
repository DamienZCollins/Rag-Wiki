package com.ragwiki.common.model.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAI 兼容协议里的工具调用，即 assistant 消息中的 tool_calls 元素。
 * arguments 在协议里是 JSON 字符串而不是对象，这里保持原样，解析交给工具层。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCallRequest(String id, String type, FunctionCall function) {

    public static final String TYPE_FUNCTION = "function";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FunctionCall(String name, String arguments) {
    }

    @JsonCreator
    public static ToolCallRequest of(String id, String name, String arguments) {
        return new ToolCallRequest(id, TYPE_FUNCTION, new FunctionCall(name, arguments));
    }

    /** 便捷读取，名字不带 get/is 前缀，不会被 Jackson 当成属性 */
    public String name() {
        return function == null ? null : function.name();
    }

    public String arguments() {
        return function == null ? null : function.arguments();
    }
}
