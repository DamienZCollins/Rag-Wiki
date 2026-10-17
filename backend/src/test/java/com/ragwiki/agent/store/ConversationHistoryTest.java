package com.ragwiki.agent.store;

import com.ragwiki.agent.protocol.Event;
import com.ragwiki.common.model.dto.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件回放与截断。协议与回放都不依赖 Spring 上下文，可以直接构造数据来测。
 */
class ConversationHistoryTest {

    @Test
    void replaysTextOnlyTurn() {
        List<Event> events = List.of(
                new Event.Started("s1", "你好"),
                new Event.Thinking(),
                new Event.TextDelta("你"),
                new Event.TextDelta("好"),
                new Event.Finished(Event.FinishReason.COMPLETED));

        List<ChatMessage> messages = ConversationHistory.replayEvents(events);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo(ChatMessage.ROLE_USER);
        assertThat(messages.get(0).content()).isEqualTo("你好");
        assertThat(messages.get(1).role()).isEqualTo(ChatMessage.ROLE_ASSISTANT);
        assertThat(messages.get(1).content()).isEqualTo("你好");
        assertThat(messages.get(1).toolCalls()).isNull();
    }

    @Test
    void groupsTextAndToolCallsIntoOneAssistantMessage() {
        List<Event> events = List.of(
                new Event.Started("s1", "订单到哪了"),
                new Event.TextDelta("我查一下"),
                new Event.ToolCall("call_1", "query_order", "{\"orderNo\":\"SO1\"}"),
                new Event.ToolResult("call_1", "query_order", true, "状态：已签收"),
                new Event.TextDelta("已经签收了"),
                new Event.Finished(Event.FinishReason.COMPLETED));

        List<ChatMessage> messages = ConversationHistory.replayEvents(events);

        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).role()).isEqualTo(ChatMessage.ROLE_USER);

        ChatMessage withTools = messages.get(1);
        assertThat(withTools.role()).isEqualTo(ChatMessage.ROLE_ASSISTANT);
        assertThat(withTools.content()).isEqualTo("我查一下");
        assertThat(withTools.toolCalls()).hasSize(1);
        assertThat(withTools.toolCalls().get(0).name()).isEqualTo("query_order");

        ChatMessage toolResult = messages.get(2);
        assertThat(toolResult.role()).isEqualTo(ChatMessage.ROLE_TOOL);
        assertThat(toolResult.toolCallId()).isEqualTo("call_1");
        assertThat(toolResult.content()).isEqualTo("状态：已签收");

        assertThat(messages.get(3).role()).isEqualTo(ChatMessage.ROLE_ASSISTANT);
        assertThat(messages.get(3).content()).isEqualTo("已经签收了");
    }

    @Test
    void interruptedTurnStillFlushesAssistantText() {
        // 客户端断开时不会发 Finished，回放要照样把已经说出口的话还原出来
        List<Event> events = List.of(
                new Event.Started("s1", "你好"),
                new Event.TextDelta("说到一半"));

        List<ChatMessage> messages = ConversationHistory.replayEvents(events);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).content()).isEqualTo("说到一半");
    }

    @Test
    void fitToWindowKeepsToolGroupIntact() {
        List<ChatMessage> messages = List.of(
                ChatMessage.user("第一轮"),
                ChatMessage.assistant("回答一", null),
                ChatMessage.user("订单呢"),
                ChatMessage.assistant(null, List.of(
                        com.ragwiki.common.model.dto.ToolCallRequest.of("call_1", "query_order", "{}"))),
                ChatMessage.tool("call_1", "结果"),
                ChatMessage.assistant("回答二", null));

        List<ChatMessage> window = ConversationHistory.fitToWindow(messages, 3);

        // 窗口正好落在工具调用组的开头，组是完整的，整组保留
        assertThat(window).hasSize(3);
        assertThat(window.get(0).isAssistantWithToolCalls()).isTrue();
        assertThat(window.get(1).role()).isEqualTo(ChatMessage.ROLE_TOOL);
        assertThat(window.get(2).content()).isEqualTo("回答二");
    }

    @Test
    void fitToWindowDropsIncompleteToolGroup() {
        // 断流留下的残局：assistant 请求了两个调用，只落地了一个结果
        List<ChatMessage> messages = List.of(
                ChatMessage.user("订单呢"),
                ChatMessage.assistant(null, List.of(
                        com.ragwiki.common.model.dto.ToolCallRequest.of("call_1", "query_order", "{}"),
                        com.ragwiki.common.model.dto.ToolCallRequest.of("call_2", "query_order", "{}"))),
                ChatMessage.tool("call_1", "结果"),
                ChatMessage.assistant("我看看"));

        List<ChatMessage> window = ConversationHistory.fitToWindow(messages, 10);

        // 不完整的一组连带着已落地的 tool 消息一起丢掉，剩下的序列模型才收
        assertThat(window).hasSize(2);
        assertThat(window.get(0).content()).isEqualTo("订单呢");
        assertThat(window.get(1).content()).isEqualTo("我看看");
    }

    @Test
    void fitToWindowDropsOrphanToolMessageAtHead() {
        List<ChatMessage> messages = List.of(
                ChatMessage.tool("call_1", "孤立的工具结果"),
                ChatMessage.assistant("回答", null));

        List<ChatMessage> window = ConversationHistory.fitToWindow(messages, 10);

        assertThat(window).hasSize(1);
        assertThat(window.get(0).content()).isEqualTo("回答");
    }

    @Test
    void fitToWindowKeepsEverythingWhenWithinLimit() {
        List<ChatMessage> messages = List.of(ChatMessage.user("你好"), ChatMessage.assistant("你好", null));

        assertThat(ConversationHistory.fitToWindow(messages, 10)).isEqualTo(messages);
    }
}
