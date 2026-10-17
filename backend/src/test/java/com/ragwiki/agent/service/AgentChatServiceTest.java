package com.ragwiki.agent.service;

import com.ragwiki.agent.api.AgentChatApi;
import com.ragwiki.agent.loop.AgentLoop;
import com.ragwiki.agent.loop.EventSink;
import com.ragwiki.agent.protocol.Event;
import com.ragwiki.agent.protocol.Op;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 一轮对话的事件折算。工具调用必须按 callId 与结果配对，
 * 配错了会让「工具参数判定」报出并不存在的错。
 */
class AgentChatServiceTest {

    /** 按预设事件流回放的内核替身。真实内核先发全部 TOOL_CALL，再逐个发 TOOL_RESULT */
    private static AgentChatApi.TurnResult runWith(List<Event> script) {
        AgentLoop loop = mock(AgentLoop.class);
        doAnswer((Answer<Void>) invocation -> {
            EventSink sink = invocation.getArgument(1);
            for (Event event : script) {
                sink.emit(event);
            }
            return null;
        }).when(loop).run(any(Op.class), any(EventSink.class));
        return new AgentChatService(loop).runTurn("s-1", "问题");
    }

    @Test
    void pairsEachToolResultWithItsOwnCall() {
        // 多工具那一轮：两条 TOOL_CALL 先发出来，再发两条 TOOL_RESULT。
        // 这条曾是 bug——收集器只留一个待配对槽位，后一个调用把前一个覆盖掉，
        // 两个结果都配上最后一个调用的名字与参数，order-013 等用例因此被报出
        // 并不存在的「订单号传错」
        AgentChatApi.TurnResult result = runWith(List.of(
                new Event.Started("s-1", "问题"),
                new Event.Thinking(),
                new Event.ToolCall("call-a", "query_order", "{\"orderNo\": \"SO20250925005\"}"),
                new Event.ToolCall("call-b", "search_knowledge", "{\"question\": \"大闸蟹能退吗\"}"),
                new Event.ToolResult("call-a", "query_order", true, "状态：退款处理中"),
                new Event.ToolResult("call-b", "search_knowledge", true, "政策片段"),
                new Event.Finished(Event.FinishReason.COMPLETED, 10, 5)));

        assertThat(result.toolInvocations()).hasSize(2);
        assertThat(result.toolInvocations().get(0).name()).isEqualTo("query_order");
        assertThat(result.toolInvocations().get(0).arguments()).contains("SO20250925005");
        assertThat(result.toolInvocations().get(1).name()).isEqualTo("search_knowledge");
        assertThat(result.toolInvocations().get(1).arguments()).contains("大闸蟹能退吗");
    }

    @Test
    void pairsByCallIdNotByArrivalOrder() {
        // 按 callId 归位，不按到达顺序硬配：先发结果的是后一个调用
        AgentChatApi.TurnResult result = runWith(List.of(
                new Event.ToolCall("call-a", "query_order", "{\"orderNo\": \"SO1\"}"),
                new Event.ToolCall("call-b", "query_order", "{\"orderNo\": \"SO2\"}"),
                new Event.ToolResult("call-b", "query_order", true, "结果2"),
                new Event.ToolResult("call-a", "query_order", true, "结果1"),
                new Event.Finished(Event.FinishReason.COMPLETED, 1, 1)));

        assertThat(result.toolInvocations().get(0).arguments()).contains("SO1");
        assertThat(result.toolInvocations().get(0).result()).isEqualTo("结果1");
        assertThat(result.toolInvocations().get(1).arguments()).contains("SO2");
        assertThat(result.toolInvocations().get(1).result()).isEqualTo("结果2");
    }

    @Test
    void fastPathTurnHasNoToolCallsAndNoThinking() {
        AgentChatApi.TurnResult result = runWith(List.of(
                new Event.Started("s-1", "问题"),
                new Event.TextDelta("快通道直答"),
                new Event.Finished(Event.FinishReason.COMPLETED, 0, 0)));

        assertThat(result.fastPathHit()).isTrue();
        assertThat(result.toolInvocations()).isEmpty();
        assertThat(result.answer()).isEqualTo("快通道直答");
    }
}
