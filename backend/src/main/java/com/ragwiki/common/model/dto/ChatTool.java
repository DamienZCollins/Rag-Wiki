package com.ragwiki.common.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 提供给模型的工具声明，对应 OpenAI 兼容协议的 tools 元素。
 * parameters 是 JSON Schema，直接由工具自己给出。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatTool(String type, FunctionDef function) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FunctionDef(String name, String description, Map<String, Object> parameters) {
    }

    public static ChatTool of(String name, String description, Map<String, Object> parameters) {
        return new ChatTool(ToolCallRequest.TYPE_FUNCTION, new FunctionDef(name, description, parameters));
    }
}
