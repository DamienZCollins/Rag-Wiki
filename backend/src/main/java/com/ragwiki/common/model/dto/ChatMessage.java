package com.ragwiki.common.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI 兼容协议里的一条对话消息。
 * 字段名与协议一致（tool_calls / tool_call_id 是下划线），空字段不序列化。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
        String role,
        String content,
        @JsonProperty("tool_calls") List<ToolCallRequest> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId) {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    public static ChatMessage system(String content) {
        return new ChatMessage(ROLE_SYSTEM, content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ROLE_USER, content, null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ROLE_ASSISTANT, content, null, null);
    }

    public static ChatMessage assistant(String content, List<ToolCallRequest> toolCalls) {
        return new ChatMessage(ROLE_ASSISTANT, content, toolCalls, null);
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage(ROLE_TOOL, content, null, toolCallId);
    }

    /** is 开头会被 Jackson 当成布尔属性，这里显式排除，避免多出一个字段发给模型 */
    @JsonIgnore
    public boolean isAssistantWithToolCalls() {
        return ROLE_ASSISTANT.equals(role) && toolCalls != null && !toolCalls.isEmpty();
    }
}
