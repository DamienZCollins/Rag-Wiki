package com.ragwiki.agent.controller;

import com.ragwiki.agent.AgentProperties;
import com.ragwiki.agent.dto.ChatRequest;
import com.ragwiki.agent.loop.AgentLoop;
import com.ragwiki.agent.loop.ClientDisconnectedException;
import com.ragwiki.agent.loop.EventSink;
import com.ragwiki.agent.protocol.Op;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话接口。每一次请求推进一轮，事件以 SSE 推送，事件名取 Event 类型名。
 */
@Tag(name = "会话", description = "Agent 主循环，先试快通道，未命中转 RAG")
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AgentLoop agentLoop;
    private final AgentProperties properties;

    public ChatController(AgentLoop agentLoop, AgentProperties properties) {
        this.agentLoop = agentLoop;
        this.properties = properties;
    }

    @Operation(summary = "对话", description = """
            以 SSE 推送事件，事件名为 STARTED / THINKING / TEXT_DELTA / TOOL_CALL /
            TOOL_RESULT / FINISHED / ERROR，载荷为事件对象的 JSON。
            事件类型定义见 com.ragwiki.agent.protocol.Event。
            FINISHED 是唯一终止事件，reason 取值 COMPLETED / MAX_STEPS / CANCELLED / FAILED。""")
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(properties.sseTimeout().toMillis());
        AtomicBoolean closed = new AtomicBoolean(false);

        emitter.onCompletion(() -> {
            closed.set(true);
            log.debug("SSE 连接结束 sessionId={}", request.sessionId());
        });
        emitter.onTimeout(() -> {
            closed.set(true);
            log.info("SSE 连接超时 sessionId={}", request.sessionId());
        });
        emitter.onError(error -> {
            closed.set(true);
            log.info("SSE 连接异常 sessionId={}：{}", request.sessionId(), error.getMessage());
        });

        EventSink sink = event -> {
            if (closed.get()) {
                throw new ClientDisconnectedException("客户端已断开");
            }
            try {
                emitter.send(SseEmitter.event()
                        .name(event.type().name())
                        .data(event, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                closed.set(true);
                throw new ClientDisconnectedException("SSE 写入失败：" + e.getMessage());
            }
        };

        // 用虚拟线程承载，内核阻塞在模型流式读取上，不占平台线程
        Thread.ofVirtual().name("agent-loop-" + request.sessionId()).start(() -> {
            try {
                agentLoop.run(new Op.UserInput(request.sessionId(), request.message()), sink);
                if (!closed.get()) {
                    emitter.complete();
                }
            } catch (ClientDisconnectedException e) {
                log.debug("客户端断开，生成已中断 sessionId={}", request.sessionId());
            } catch (Exception e) {
                log.error("对话处理失败 sessionId={}", request.sessionId(), e);
                if (!closed.get()) {
                    emitter.completeWithError(e);
                }
            }
        });

        return emitter;
    }
}
