package com.ragwiki.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragwiki.agent.AgentProperties;
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
import com.ragwiki.common.model.dto.ChatTool;
import com.ragwiki.common.model.dto.ChatUsage;
import com.ragwiki.common.model.dto.ToolCallRequest;
import com.ragwiki.agent.tool.ToolContext;
import com.ragwiki.wiki.api.FastPathApi;
import com.ragwiki.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 双层主循环。
 *
 * <p>外层是会话级的一次推进：装载会话与历史 → 落用户消息 → 交给内层 → 落 assistant 消息 → 终止。
 * 每来一次 {@link Op} 推进一轮，而不是在一个长连接里循环等输入，因为接口是「一次请求一轮对话」。
 *
 * <p>内层是单轮内的模型往返：调模型 → 有工具调用就执行并回填 → 再调模型，直到模型不再请求工具，
 * 或达到步数上限。步数上限用来兜住工具调用不收敛的情况。
 *
 * <p>落库失败不影响输出，只记日志：事件与消息都是记录，不该反过来卡住对话。
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private static final String SYSTEM_PROMPT = """
            你是电商平台的售后客服助手。
            涉及具体订单的问题必须调用 query_order 查真实数据，不要凭记忆回答订单信息。
            涉及售后政策、规则、时效、费用标准的问题必须调用 search_knowledge 查政策原文，
            查到依据后在结论后用 [编号] 标注出处，只引用检索结果里出现过的编号。
            检索结果里没有依据时，如实说明你查不到这条政策，不要凭常识或猜测作答，并提出转人工核实。
            回答简洁、口语化，直接给结论，不要提到工具、函数、系统提示这些内部实现。
            """;

    private static final String ROLE_USER = "USER";
    private static final String ROLE_ASSISTANT = "ASSISTANT";
    private static final String ROLE_TOOL = "TOOL";
    private static final String FINISH_REASON_LENGTH = "length";

    private final ChatClient chatClient;
    private final ToolRegistry toolRegistry;
    private final ConversationStore conversationStore;
    private final EventStore eventStore;
    private final ConversationHistory conversationHistory;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final FastPathApi fastPathApi;
    private final SessionTurnLock turnLock;

    public AgentLoop(ChatClient chatClient,
                     ToolRegistry toolRegistry,
                     ConversationStore conversationStore,
                     EventStore eventStore,
                     ConversationHistory conversationHistory,
                     AgentProperties properties,
                     ObjectMapper objectMapper,
                     FastPathApi fastPathApi,
                     SessionTurnLock turnLock) {
        this.chatClient = chatClient;
        this.toolRegistry = toolRegistry;
        this.conversationStore = conversationStore;
        this.eventStore = eventStore;
        this.conversationHistory = conversationHistory;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.fastPathApi = fastPathApi;
        this.turnLock = turnLock;
    }

    public void run(Op op, EventSink sink) {
        switch (op) {
            case Op.UserInput input -> handleUserInput(input, sink);
        }
    }

    /**
     * 一轮对话的入口。同一会话已有回合在跑时直接拒绝：会话内序号按当前最大值 +1 分配，
     * 两轮并发会撞唯一键。拒绝的一轮不落库，免得和正在跑的那轮抢序号。
     */
    private void handleUserInput(Op.UserInput input, EventSink sink) {
        if (!turnLock.tryAcquire(input.sessionId())) {
            sink.emit(new Event.Error(String.valueOf(ResultCode.AGENT_SESSION_BUSY.code()),
                    ResultCode.AGENT_SESSION_BUSY.message()));
            sink.emit(new Event.Finished(Event.FinishReason.FAILED));
            return;
        }
        try {
            advance(input, sink);
        } finally {
            turnLock.release(input.sessionId());
        }
    }

    private void advance(Op.UserInput input, EventSink sink) {
        Long conversationId = openConversation(input);

        // 历史要在发出 Started 之前恢复：Started 属于本轮，不该出现在本轮的历史里
        List<ChatMessage> context = new ArrayList<>();
        context.add(ChatMessage.system(SYSTEM_PROMPT));
        if (conversationId != null) {
            try {
                context.addAll(conversationHistory.restore(conversationId));
            } catch (Exception e) {
                log.error("会话历史恢复失败，按空上下文继续 conversationId={}", conversationId, e);
            }
        }
        context.add(ChatMessage.user(input.text()));

        EventRecorder recorder = new EventRecorder(conversationId, sink);
        ChatUsage[] usage = {ChatUsage.EMPTY};
        long startedAtNanos = System.nanoTime();
        // 快通道有没有答上：答上了就不再记慢通道日志。内核是单例，这类状态必须是局部的
        boolean fastPathHit = false;
        try {
            recorder.emit(new Event.Started(input.sessionId(), input.text()));
            persistMessage(conversationId, ROLE_USER, input.text(), null, null);

            // 先试快通道：命中就直答，全程零模型调用、零向量检索
            FastPathApi.Answer fast = tryFastPath(input.text());
            fastPathHit = fast.hit();
            if (fastPathHit) {
                log.debug("快通道命中 {}，本轮不调用模型", fast.entryId());
                recorder.emit(new Event.TextDelta(fast.answer()));
                persistMessage(conversationId, ROLE_ASSISTANT, fast.answer(), null, null);
                recorder.emit(new Event.Finished(Event.FinishReason.COMPLETED));
                return;
            }

            runInnerLoop(conversationId, input.sessionId(), context, recorder, usage);
        } catch (ClientDisconnectedException e) {
            log.info("客户端已断开，终止生成 sessionId={}", input.sessionId());
        } catch (BusinessException e) {
            log.warn("内核处理失败 sessionId={} code={} message={}",
                    input.sessionId(), e.getCode(), e.getMessage());
            recorder.emitQuietly(new Event.Error(String.valueOf(e.getCode()), e.getMessage()));
            recorder.emitQuietly(new Event.Finished(Event.FinishReason.FAILED));
        } catch (Exception e) {
            log.error("内核未预期异常 sessionId={}", input.sessionId(), e);
            recorder.emitQuietly(new Event.Error(
                    String.valueOf(ResultCode.SYSTEM_ERROR.code()), ResultCode.SYSTEM_ERROR.message()));
            recorder.emitQuietly(new Event.Finished(Event.FinishReason.FAILED));
        } finally {
            // 放在 finally 是为了失败的一轮也留下记录
            if (!fastPathHit) {
                recordSlowPath(input.text(), startedAtNanos, usage[0], conversationId);
            }
        }
    }

    /** 快通道自己出问题不能把整轮卡死，异常一律当作未命中，降级走 RAG */
    private FastPathApi.Answer tryFastPath(String question) {
        try {
            return fastPathApi.answer(question);
        } catch (Exception e) {
            log.error("快通道异常，降级到 RAG：{}", e.getMessage());
            return FastPathApi.Answer.miss();
        }
    }

    private void recordSlowPath(String question, long startedAtNanos, ChatUsage usage, Long conversationId) {
        long latencyMillis = (System.nanoTime() - startedAtNanos) / 1_000_000;
        try {
            fastPathApi.recordSlowPath(question, latencyMillis,
                    usage.prompt(), usage.completion(), conversationId);
        } catch (Exception e) {
            log.error("慢通道提问日志记录失败，不影响本轮输出", e);
        }
    }

    private Long openConversation(Op.UserInput input) {
        try {
            return conversationStore.getOrCreate(input.sessionId(), input.text()).getId();
        } catch (Exception e) {
            log.error("会话创建或读取失败，本轮不落库 sessionId={}", input.sessionId(), e);
            return null;
        }
    }

    private void runInnerLoop(Long conversationId, String sessionId, List<ChatMessage> context,
                              EventRecorder recorder, ChatUsage[] usage) {
        List<ChatTool> tools = toolRegistry.specs();

        for (int step = 1; step <= properties.maxSteps(); step++) {
            recorder.emit(new Event.Thinking());
            ChatCompletion completion = chatClient.stream(context, tools,
                    delta -> recorder.emit(new Event.TextDelta(delta)));
            usage[0] = usage[0].plus(completion.usageOrEmpty());

            if (!completion.hasToolCalls()) {
                if (FINISH_REASON_LENGTH.equals(completion.finishReason())) {
                    // 推理模型的思考 token 也算进 max-tokens，回答可能被截断，这里只留痕不改变事件契约
                    log.warn("模型输出达到长度上限被截断 conversationId={} maxTokens 配置见 model.chat.max-tokens",
                            conversationId);
                }
                persistMessage(conversationId, ROLE_ASSISTANT, completion.content(), null, null);
                recorder.emit(new Event.Finished(Event.FinishReason.COMPLETED));
                return;
            }

            context.add(ChatMessage.assistant(completion.content(), completion.toolCalls()));
            persistMessage(conversationId, ROLE_ASSISTANT, completion.content(),
                    writeToolCalls(completion.toolCalls()), null);

            // 先把这一批工具调用全部发出去，再逐个执行。
            // 模型是在同一条 assistant 消息里请求多个工具的，先发全部才能让事件流忠实记录这条消息，
            // 回放时才不会把一条消息拆成几条
            for (ToolCallRequest call : completion.toolCalls()) {
                recorder.emit(new Event.ToolCall(call.id(), call.name(), call.arguments()));
            }

            for (ToolCallRequest call : completion.toolCalls()) {
                ToolOutcome outcome = toolRegistry.invoke(call.name(), call.arguments(),
                        new ToolContext(sessionId, conversationId));
                recorder.emit(new Event.ToolResult(call.id(), call.name(), outcome.success(), outcome.content()));

                context.add(ChatMessage.tool(call.id(), outcome.content()));
                persistMessage(conversationId, ROLE_TOOL, outcome.content(), null, call.id());
            }
        }

        log.warn("达到步数上限 {}，工具调用仍未收敛", properties.maxSteps());
        recorder.emit(new Event.Error("MAX_STEPS", "问题较复杂，建议转人工处理"));
        recorder.emit(new Event.Finished(Event.FinishReason.MAX_STEPS));
    }

    private void persistMessage(Long conversationId, String role, String content,
                                String toolCallsJson, String toolCallId) {
        if (conversationId == null) {
            return;
        }
        try {
            conversationStore.appendMessage(conversationId, role, content, toolCallsJson, toolCallId);
        } catch (Exception e) {
            log.error("消息落库失败，不影响本次输出 conversationId={} role={}", conversationId, role, e);
        }
    }

    private String writeToolCalls(List<ToolCallRequest> toolCalls) {
        try {
            return objectMapper.writeValueAsString(toolCalls);
        } catch (Exception e) {
            log.error("工具调用载荷序列化失败", e);
            return null;
        }
    }

    /**
     * 事件出口：先落库再推送。
     * 先落库保证「说过什么」不依赖客户端是否还在，落库失败只记日志，不挡推送。
     */
    private final class EventRecorder {

        private final Long conversationId;
        private final EventSink sink;
        private int nextSeq;

        private EventRecorder(Long conversationId, EventSink sink) {
            this.conversationId = conversationId;
            this.sink = sink;
            if (conversationId != null) {
                try {
                    this.nextSeq = eventStore.nextSeq(conversationId);
                } catch (Exception e) {
                    log.error("事件序号读取失败，本轮事件不落库 conversationId={}", conversationId, e);
                }
            }
        }

        void emit(Event event) {
            persist(event);
            sink.emit(event);
        }

        /** 收尾用：客户端可能已经断开，推不出去是正常的，不再往上抛 */
        void emitQuietly(Event event) {
            persist(event);
            try {
                sink.emit(event);
            } catch (ClientDisconnectedException ignored) {
                // 已经断开，收尾事件推不出去
            }
        }

        private void persist(Event event) {
            if (conversationId == null || nextSeq == 0) {
                return;
            }
            try {
                eventStore.append(conversationId, nextSeq, event);
                nextSeq++;
            } catch (Exception e) {
                log.error("事件落库失败，不影响本次输出 conversationId={} type={}",
                        conversationId, event.type(), e);
                nextSeq = 0;
            }
        }
    }
}
