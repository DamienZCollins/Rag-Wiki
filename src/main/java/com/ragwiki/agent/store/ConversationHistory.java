package com.ragwiki.agent.store;

import com.ragwiki.agent.AgentProperties;
import com.ragwiki.agent.protocol.Event;
import com.ragwiki.common.model.dto.ChatMessage;
import com.ragwiki.common.model.dto.ToolCallRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 按事件回放恢复会话上下文。
 * 事实来源是 agent_event，message 表只是展示投影，不参与恢复。
 */
@Component
public class ConversationHistory {

    private final EventStore eventStore;
    private final AgentProperties properties;

    public ConversationHistory(EventStore eventStore, AgentProperties properties) {
        this.eventStore = eventStore;
        this.properties = properties;
    }

    public List<ChatMessage> restore(long conversationId) {
        return fitToWindow(replayEvents(eventStore.replay(conversationId)), properties.maxHistoryMessages());
    }

    /**
     * 把一串事件还原成模型能吃的消息序列。
     * TEXT_DELTA 与紧随其后的 TOOL_CALL 属于同一条 assistant 消息，攒到一起再落；
     * TOOL_RESULT 表示这一组工具调用结束，先把 assistant 消息冲出去，再落工具结果。
     */
    static List<ChatMessage> replayEvents(List<Event> events) {
        List<ChatMessage> messages = new ArrayList<>();
        StringBuilder assistantText = new StringBuilder();
        List<ToolCallRequest> pendingToolCalls = new ArrayList<>();

        for (Event event : events) {
            switch (event) {
                case Event.Started started -> {
                    flushAssistant(messages, assistantText, pendingToolCalls);
                    messages.add(ChatMessage.user(started.userMessage()));
                }
                case Event.TextDelta delta -> assistantText.append(delta.text());
                case Event.ToolCall call -> pendingToolCalls.add(
                        ToolCallRequest.of(call.callId(), call.name(), call.arguments()));
                case Event.ToolResult result -> {
                    flushAssistant(messages, assistantText, pendingToolCalls);
                    messages.add(ChatMessage.tool(result.callId(), result.content()));
                }
                case Event.Finished ignored -> flushAssistant(messages, assistantText, pendingToolCalls);
                case Event.Error ignored -> flushAssistant(messages, assistantText, pendingToolCalls);
                case Event.Thinking ignored -> {
                    // 纯信号，不产生消息
                }
            }
        }
        flushAssistant(messages, assistantText, pendingToolCalls);
        return messages;
    }

    private static void flushAssistant(List<ChatMessage> messages,
                                       StringBuilder assistantText,
                                       List<ToolCallRequest> pendingToolCalls) {
        if (assistantText.isEmpty() && pendingToolCalls.isEmpty()) {
            return;
        }
        messages.add(ChatMessage.assistant(
                assistantText.isEmpty() ? null : assistantText.toString(),
                pendingToolCalls.isEmpty() ? null : List.copyOf(pendingToolCalls)));
        assistantText.setLength(0);
        pendingToolCalls.clear();
    }

    /**
     * 历史过长时只保留最近若干条，不无脑全量拼进 prompt；截断后再做一次结构归一。
     */
    static List<ChatMessage> fitToWindow(List<ChatMessage> messages, int maxMessages) {
        List<ChatMessage> window = messages;
        if (maxMessages > 0 && messages.size() > maxMessages) {
            window = messages.subList(messages.size() - maxMessages, messages.size());
        }
        return normalize(window);
    }

    /**
     * 归一成模型能接受的消息序列。
     * 工具调用必须「一条 assistant 消息带全部 tool_calls，紧跟每个调用的 tool 消息」成组出现，
     * 断流或截断会在组内留下缺口，模型会直接拒收，所以不完整的一组整体丢掉，没有归属的 tool 消息也丢掉。
     */
    static List<ChatMessage> normalize(List<ChatMessage> messages) {
        List<ChatMessage> result = new ArrayList<>(messages.size());
        int index = 0;
        while (index < messages.size()) {
            ChatMessage message = messages.get(index);

            if (message.isAssistantWithToolCalls()) {
                int groupEnd = index + 1;
                Set<String> answered = new HashSet<>();
                while (groupEnd < messages.size()
                        && ChatMessage.ROLE_TOOL.equals(messages.get(groupEnd).role())) {
                    answered.add(messages.get(groupEnd).toolCallId());
                    groupEnd++;
                }
                boolean complete = message.toolCalls().stream()
                        .allMatch(call -> answered.contains(call.id()));
                if (complete) {
                    result.add(message);
                    result.addAll(messages.subList(index + 1, groupEnd));
                }
                index = groupEnd;
                continue;
            }

            if (ChatMessage.ROLE_TOOL.equals(message.role())) {
                index++;
                continue;
            }

            result.add(message);
            index++;
        }
        return List.copyOf(result);
    }
}
