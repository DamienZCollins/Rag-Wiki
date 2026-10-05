package com.ragwiki.common.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 模型调用的 token 消耗。双通道对比要用它算省了多少 token。
 * 流式响应里 usage 通常在最后一个分片，也可能伴随 finish_reason 一起给。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatUsage(
        @JsonProperty("prompt_tokens") Integer promptTokens,
        @JsonProperty("completion_tokens") Integer completionTokens,
        @JsonProperty("total_tokens") Integer totalTokens) {

    public static final ChatUsage EMPTY = new ChatUsage(0, 0, 0);

    public int prompt() {
        return promptTokens == null ? 0 : promptTokens;
    }

    public int completion() {
        return completionTokens == null ? 0 : completionTokens;
    }

    public ChatUsage plus(ChatUsage other) {
        if (other == null) {
            return this;
        }
        return new ChatUsage(prompt() + other.prompt(), completion() + other.completion(),
                (totalTokens == null ? 0 : totalTokens) + (other.totalTokens() == null ? 0 : other.totalTokens()));
    }
}
