package com.ragwiki.agent.service;

import com.ragwiki.agent.api.AgentChatApi;
import com.ragwiki.agent.loop.AgentLoop;
import com.ragwiki.agent.loop.EventSink;
import com.ragwiki.agent.protocol.Event;
import com.ragwiki.agent.protocol.Op;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link AgentChatApi} 的实现：把内核的事件收在内存里，折算成一轮对话的结构化结果。
 *
 * <p>走的是与 HTTP 入口完全相同的 {@link AgentLoop}，所以评测量到的行为就是线上行为，
 * 不存在「评测专用链路」与真实链路分叉的问题。
 */
@Service
public class AgentChatService implements AgentChatApi {

    private final AgentLoop agentLoop;

    public AgentChatService(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    @Override
    public TurnResult runTurn(String sessionId, String message) {
        Collector collector = new Collector();
        agentLoop.run(new Op.UserInput(sessionId, message), collector);
        return collector.toResult();
    }

    /** 事件收集器。THINKING 的有无是判断走没走快通道的依据 */
    private static final class Collector implements EventSink {

        private final StringBuilder answer = new StringBuilder();
        /** 按调用顺序排的调用槽位，结果乱序到达也能回到原位 */
        private final List<CallSlot> calls = new ArrayList<>();
        private final Map<String, CallSlot> callsById = new LinkedHashMap<>();
        private int tokenIn;
        private int tokenOut;
        private String finishReason;
        private boolean thinking;
        private String error;

        /** 一次工具调用。先由 TOOL_CALL 建槽，TOOL_RESULT 按 callId 回填 */
        private static final class CallSlot {
            private final String name;
            private final String arguments;
            private boolean success;
            private String content;
            private boolean filled;

            private CallSlot(String name, String arguments) {
                this.name = name;
                this.arguments = arguments;
            }
        }

        @Override
        public void emit(Event event) {
            switch (event) {
                case Event.Started ignored -> {
                    // 一轮开始，没有需要收集的状态
                }
                case Event.Thinking ignored -> thinking = true;
                case Event.TextDelta delta -> answer.append(delta.text());
                // 不能只留一个「待配对」的槽位：多工具那一轮所有 TOOL_CALL 都在任何
                // TOOL_RESULT 之前发出，单槽位会被后一个调用覆盖，结果是每个结果都配上
                // 最后一个调用的名字与参数，工具参数判定会报出并不存在的错
                case Event.ToolCall call -> {
                    CallSlot slot = new CallSlot(call.name(), call.arguments());
                    calls.add(slot);
                    callsById.put(call.callId(), slot);
                }
                case Event.ToolResult result -> {
                    CallSlot slot = callsById.get(result.callId());
                    if (slot == null) {
                        // 没有对应调用（不该发生）：按结果自带的名字单独记一条，不丢信息
                        slot = new CallSlot(result.name(), null);
                        calls.add(slot);
                    }
                    slot.success = result.success();
                    slot.content = result.content();
                    slot.filled = true;
                }
                case Event.Finished finished -> {
                    finishReason = finished.reason().name();
                    tokenIn = finished.tokenIn();
                    tokenOut = finished.tokenOut();
                }
                case Event.Error e -> error = e.code() + "：" + e.message();
            }
        }

        private TurnResult toResult() {
            // 按调用顺序输出，跳过没等到结果的（步数触顶时会有）
            List<ToolInvocation> invocations = new ArrayList<>(calls.size());
            for (CallSlot slot : calls) {
                if (slot.filled) {
                    invocations.add(new ToolInvocation(
                            slot.name, slot.arguments, slot.success, slot.content));
                }
            }
            return new TurnResult(
                    answer.toString(),
                    List.copyOf(invocations),
                    tokenIn,
                    tokenOut,
                    finishReason == null ? "FAILED" : finishReason,
                    !thinking,
                    error);
        }
    }
}
