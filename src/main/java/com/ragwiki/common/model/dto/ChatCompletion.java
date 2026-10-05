package com.ragwiki.common.model.dto;

import java.util.List;

/**
 * 一次流式对话的最终结果。文本增量在流式过程中已经逐块回调出去，这里给的是拼接后的完整值。
 */
public record ChatCompletion(String content, List<ToolCallRequest> toolCalls, String finishReason) {

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
