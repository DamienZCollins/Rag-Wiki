package com.ragwiki.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragwiki.agent.AgentProperties;
import com.ragwiki.agent.entity.Conversation;
import com.ragwiki.agent.protocol.Event;
import com.ragwiki.agent.protocol.Op;
import com.ragwiki.agent.store.ConversationHistory;
import com.ragwiki.agent.store.ConversationStore;
import com.ragwiki.agent.store.EventStore;
import com.ragwiki.agent.tool.ToolOutcome;
import com.ragwiki.agent.tool.ToolRegistry;
import com.ragwiki.common.exception.BusinessException;
import com.ragwiki.common.model.ChatClient;
import com.ragwiki.common.model.dto.ChatCompletion;
import com.ragwiki.common.model.dto.ChatMessage;
import com.ragwiki.common.model.dto.ToolCallRequest;
import com.ragwiki.common.result.ResultCode;
import com.ragwiki.wiki.api.FastPathApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 双层主循环。内核只依赖接口，不启 Spring 上下文就能测。
 */
class AgentLoopTest {

    private static final int MAX_STEPS = 3;

    private final ChatClient chatClient = mock(ChatClient.class);
    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
    private final ConversationStore conversationStore = mock(ConversationStore.class);
    private final EventStore eventStore = mock(EventStore.class);
    private final ConversationHistory conversationHistory = mock(ConversationHistory.class);
    private final FastPathApi fastPathApi = mock(FastPathApi.class);

    private AgentLoop agentLoop;
    private List<Event> events;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties(MAX_STEPS, Duration.ofSeconds(60), 40);
        agentLoop = new AgentLoop(chatClient, toolRegistry, conversationStore, eventStore,
                conversationHistory, properties, new ObjectMapper(), fastPathApi);
        // 这些用例针对的是慢通道的内层循环，快通道一律判未命中
        when(fastPathApi.answer(anyString())).thenReturn(FastPathApi.Answer.miss());

        Conversation conversation = new Conversation();
        conversation.setId(1L);
        when(conversationStore.getOrCreate(anyString(), anyString())).thenReturn(conversation);
        when(conversationHistory.restore(anyLong())).thenReturn(List.of());
        when(eventStore.nextSeq(anyLong())).thenReturn(1);
        when(toolRegistry.specs()).thenReturn(List.of());

        events = new ArrayList<>();
    }

    private void run(String message) {
        agentLoop.run(new Op.UserInput("s1", message), events::add);
    }

    private List<Event.EventType> types() {
        return events.stream().map(Event::type).toList();
    }

    @Test
    void finishesInOneRoundWhenModelDoesNotCallTools() {
        when(chatClient.stream(any(), any(), any())).thenAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(2);
            onDelta.accept("你好");
            onDelta.accept("，有什么可以帮你");
            return new ChatCompletion("你好，有什么可以帮你", List.of(), "stop");
        });

        run("你好");

        assertThat(types()).containsExactly(
                Event.EventType.STARTED,
                Event.EventType.THINKING,
                Event.EventType.TEXT_DELTA,
                Event.EventType.TEXT_DELTA,
                Event.EventType.FINISHED);
        Event.Finished finished = (Event.Finished) events.get(events.size() - 1);
        assertThat(finished.reason()).isEqualTo(Event.FinishReason.COMPLETED);
    }

    @Test
    void feedsToolResultBackAndContinuesToNextRound() {
        List<List<ChatMessage>> seenContexts = new ArrayList<>();
        when(toolRegistry.invoke(eq("query_order"), anyString(), any()))
                .thenReturn(ToolOutcome.ok("状态：已签收"));

        when(chatClient.stream(any(), any(), any())).thenAnswer(invocation -> {
            List<ChatMessage> context = invocation.getArgument(0);
            seenContexts.add(List.copyOf(context));
            if (seenContexts.size() == 1) {
                return new ChatCompletion(null, List.of(
                        ToolCallRequest.of("call_1", "query_order", "{\"orderNo\":\"SO1\"}")), "tool_calls");
            }
            Consumer<String> onDelta = invocation.getArgument(2);
            onDelta.accept("已经签收了");
            return new ChatCompletion("已经签收了", List.of(), "stop");
        });

        run("订单 SO1 到哪了");

        assertThat(types()).containsExactly(
                Event.EventType.STARTED,
                Event.EventType.THINKING,
                Event.EventType.TOOL_CALL,
                Event.EventType.TOOL_RESULT,
                Event.EventType.THINKING,
                Event.EventType.TEXT_DELTA,
                Event.EventType.FINISHED);
        Event.ToolResult toolResult = (Event.ToolResult) events.get(3);
        assertThat(toolResult.name()).isEqualTo("query_order");
        assertThat(toolResult.success()).isTrue();

        // 第二轮的上下文里能看到工具结果，模型才知道该说什么
        List<ChatMessage> secondContext = seenContexts.get(1);
        assertThat(secondContext.get(secondContext.size() - 1).role()).isEqualTo(ChatMessage.ROLE_TOOL);
        assertThat(secondContext.get(secondContext.size() - 1).content()).isEqualTo("状态：已签收");
    }

    @Test
    void stopsAtMaxStepsWhenToolCallsNeverConverge() {
        when(toolRegistry.invoke(anyString(), anyString(), any())).thenReturn(ToolOutcome.ok("结果"));
        when(chatClient.stream(any(), any(), any())).thenReturn(new ChatCompletion(null, List.of(
                ToolCallRequest.of("call_1", "query_order", "{}")), "tool_calls"));

        run("无限工具调用");

        Event.Finished finished = (Event.Finished) events.get(events.size() - 1);
        assertThat(finished.reason()).isEqualTo(Event.FinishReason.MAX_STEPS);
        long modelCalls = types().stream().filter(type -> type == Event.EventType.THINKING).count();
        assertThat(modelCalls).isEqualTo(MAX_STEPS);
    }

    @Test
    void modelFailureEndsStreamWithError() {
        when(chatClient.stream(any(), any(), any()))
                .thenThrow(new BusinessException(ResultCode.MODEL_TIMEOUT, "模型调用超时"));

        run("你好");

        assertThat(types()).containsExactly(
                Event.EventType.STARTED,
                Event.EventType.THINKING,
                Event.EventType.ERROR,
                Event.EventType.FINISHED);
        Event.Error error = (Event.Error) events.get(2);
        assertThat(error.code()).isEqualTo(String.valueOf(ResultCode.MODEL_TIMEOUT.code()));
        Event.Finished finished = (Event.Finished) events.get(3);
        assertThat(finished.reason()).isEqualTo(Event.FinishReason.FAILED);
    }

    @Test
    void abortsWhenClientDisconnects() {
        when(chatClient.stream(any(), any(), any())).thenAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(2);
            onDelta.accept("说");
            onDelta.accept("到");
            onDelta.accept("一半");
            return new ChatCompletion("说到一半", List.of(), "stop");
        });

        List<Event> received = new ArrayList<>();
        EventSink sink = event -> {
            if (event.type() == Event.EventType.TEXT_DELTA) {
                throw new ClientDisconnectedException("客户端已断开");
            }
            received.add(event);
        };

        agentLoop.run(new Op.UserInput("s1", "你好"), sink);

        // 断开后不再产生任何事件，虚拟线程正常结束
        assertThat(types()).isEmpty();
        assertThat(received.stream().map(Event::type))
                .containsExactly(Event.EventType.STARTED, Event.EventType.THINKING);
    }
}
