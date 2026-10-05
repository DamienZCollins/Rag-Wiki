package com.ragwiki.common.model;

import com.ragwiki.common.model.dto.ChatCompletion;
import com.ragwiki.common.model.dto.ChatMessage;
import com.ragwiki.common.model.dto.ChatTool;

import java.util.List;
import java.util.function.Consumer;

/**
 * 对话模型客户端。内核只依赖这个接口，换成别的兼容实现不需要动内核。
 */
public interface ChatClient {

    /**
     * 发起一次流式对话。
     *
     * @param messages    完整上下文，含系统提示、历史与本轮用户输入
     * @param tools       可调用的工具声明，为空表示不带工具
     * @param onTextDelta 文本增量回调，每收到一个分片调用一次
     * @return 拼接后的完整结果，含最终文本与工具调用请求
     */
    ChatCompletion stream(List<ChatMessage> messages, List<ChatTool> tools, Consumer<String> onTextDelta);
}
