package com.ragwiki.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragwiki.agent.entity.AgentEvent;
import com.ragwiki.agent.entity.Conversation;
import com.ragwiki.agent.entity.OrderInfo;
import com.ragwiki.agent.mapper.AgentEventMapper;
import com.ragwiki.agent.mapper.ConversationMapper;
import com.ragwiki.agent.mapper.OrderInfoMapper;
import com.ragwiki.agent.store.ConversationHistory;
import com.ragwiki.agent.store.ConversationStore;
import com.ragwiki.common.model.dto.ChatMessage;
import com.ragwiki.support.StubModelServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对话链路的端到端验证：真实 HTTP、真实 SSE、真实 MySQL，替换掉的只有模型服务（用桩）。
 * 需要本地中间件已经启动。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatIntegrationTest {

    private static StubModelServer stub;

    @DynamicPropertySource
    static void modelProperties(DynamicPropertyRegistry registry) throws IOException {
        stub = new StubModelServer();
        registry.add("model.chat.base-url", stub::baseUrl);
        registry.add("model.chat.api-key", () -> "stub-key");
        registry.add("model.chat.model", () -> "stub-model");
        registry.add("agent.max-steps", () -> 3);
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.close();
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderInfoMapper orderInfoMapper;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private AgentEventMapper agentEventMapper;

    @Autowired
    private ConversationStore conversationStore;

    @Autowired
    private ConversationHistory conversationHistory;

    private String orderNo;
    private String sessionId;

    @BeforeEach
    void setUp() {
        orderNo = "SO" + System.nanoTime();
        sessionId = "it-" + UUID.randomUUID();

        OrderInfo order = new OrderInfo();
        order.setOrderNo(orderNo);
        order.setUserId("U-test");
        order.setStatus("DELIVERED");
        order.setProductName("测试商品");
        order.setQuantity(2);
        order.setPaidAmount(new BigDecimal("199.00"));
        Instant now = Instant.now();
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        orderInfoMapper.insert(order);
    }

    @Test
    void streamsTextAnswer() throws Exception {
        List<SseEvent> events = chat(sessionId, "你们几点上班？");
        List<String> names = names(events);

        assertThat(names).startsWith("STARTED", "THINKING");
        assertThat(names).endsWith("FINISHED");
        assertThat(names.subList(2, names.size() - 1)).containsOnly("TEXT_DELTA");
        // 桩是分片推的，必须真的逐块出来而不是攒完一次性给
        assertThat(textDeltas(events)).hasSizeGreaterThan(1);
        assertThat(String.join("", textDeltas(events))).isNotBlank();

        assertThat(json(events.get(events.size() - 1)).get("reason").asText()).isEqualTo("COMPLETED");

        // 事件名与 Event 类型一一对应
        for (SseEvent event : events) {
            assertThat(json(event).get("type").asText()).isEqualTo(event.name());
        }
    }

    @Test
    void orderQuestionTriggersRealToolCall() throws Exception {
        List<SseEvent> events = chat(sessionId, "帮我查下订单 " + orderNo + " 到哪了");
        List<String> names = names(events);

        assertThat(names).startsWith("STARTED", "THINKING", "TOOL_CALL", "TOOL_RESULT", "THINKING");
        assertThat(names).endsWith("FINISHED");
        assertThat(names.subList(5, names.size() - 1)).containsOnly("TEXT_DELTA");

        JsonNode toolCall = json(events.get(2));
        assertThat(toolCall.get("name").asText()).isEqualTo("query_order");
        assertThat(toolCall.get("arguments").asText()).contains(orderNo);

        JsonNode toolResult = json(events.get(3));
        assertThat(toolResult.get("success").asBoolean()).isTrue();
        // 工具结果来自真实数据库，不是模型编的
        assertThat(toolResult.get("content").asText())
                .contains(orderNo)
                .contains("测试商品")
                .contains("199.00");
    }

    @Test
    void restoresHistoryFromEventsOnSecondTurn() throws Exception {
        List<SseEvent> first = chat(sessionId, "帮我查下订单 " + orderNo + " 到哪了");
        assertThat(names(first)).contains("TOOL_CALL");

        // 第二轮：上下文里应当已经带上第一轮的工具结果，桩据此走「已有工具结果」分支
        List<SseEvent> second = chat(sessionId, "那我还能退吗");

        Conversation conversation = conversationMapper.selectOne(
                Wrappers.<Conversation>lambdaQuery().eq(Conversation::getSessionId, sessionId));
        List<ChatMessage> restored = conversationHistory.restore(conversation.getId());

        assertThat(restored).isNotEmpty();
        assertThat(restored.get(0).role()).isEqualTo(ChatMessage.ROLE_USER);
        assertThat(restored).anyMatch(message -> ChatMessage.ROLE_TOOL.equals(message.role()));
        assertThat(restored).anyMatch(ChatMessage::isAssistantWithToolCalls);

        assertThat(textDeltas(second)).isNotEmpty();
        assertThat(String.join("", textDeltas(second))).contains("已经帮你查到");
    }

    @Test
    void persistsMessagesAndEventsWithContinuousSeq() throws Exception {
        chat(sessionId, "帮我查下订单 " + orderNo + " 到哪了");

        Conversation conversation = conversationMapper.selectOne(
                Wrappers.<Conversation>lambdaQuery().eq(Conversation::getSessionId, sessionId));
        assertThat(conversation).isNotNull();
        assertThat(conversation.getTitle()).isNotBlank();
        assertThat(conversation.getStatus()).isEqualTo("ACTIVE");

        List<AgentEvent> events = agentEventMapper.selectList(Wrappers.<AgentEvent>lambdaQuery()
                .eq(AgentEvent::getConversationId, conversation.getId())
                .orderByAsc(AgentEvent::getSeq));
        assertThat(events).isNotEmpty();
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).getSeq()).isEqualTo(i + 1);
            assertThat(events.get(i).getEventType()).isNotBlank();
            assertThat(events.get(i).getPayload()).contains("\"type\"");
        }
        // 事件走的都是 INSERT，回放出来能和实时推的事件对上
        assertThat(events).extracting(AgentEvent::getEventType)
                .contains("STARTED", "TOOL_CALL", "TOOL_RESULT", "FINISHED");

        var messages = conversationStore.listMessages(conversation.getId());
        assertThat(messages).isNotEmpty();
        for (int i = 0; i < messages.size(); i++) {
            assertThat(messages.get(i).getSeq()).isEqualTo(i + 1);
        }
        assertThat(messages).extracting(m -> m.getRole())
                .contains("USER", "ASSISTANT", "TOOL");
    }

    @Test
    void stopsAtMaxSteps() throws Exception {
        List<SseEvent> events = chat(sessionId, "无限工具调用");

        JsonNode finished = json(events.get(events.size() - 1));
        assertThat(events.get(events.size() - 1).name()).isEqualTo("FINISHED");
        assertThat(finished.get("reason").asText()).isEqualTo("MAX_STEPS");

        long thinking = names(events).stream().filter("THINKING"::equals).count();
        assertThat(thinking).isEqualTo(3);
    }

    @Test
    void modelFailureTurnsIntoErrorEvent() throws Exception {
        List<SseEvent> events = chat(sessionId, "触发模型错误");

        assertThat(names(events)).containsExactly("STARTED", "THINKING", "ERROR", "FINISHED");
        assertThat(json(events.get(2)).get("message").asText()).contains("500");
        assertThat(json(events.get(3)).get("reason").asText()).isEqualTo("FAILED");
    }

    // ---------- 辅助 ----------

    private record SseEvent(String name, String json) {
    }

    private List<SseEvent> chat(String sessionId, String message) throws Exception {
        String body = objectMapper.writeValueAsString(new ChatBody(sessionId, message));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/chat"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("text/event-stream");
        return parse(response.body());
    }

    private record ChatBody(String sessionId, String message) {
    }

    private List<SseEvent> parse(String body) {
        List<SseEvent> events = new ArrayList<>();
        String name = null;
        String data = null;
        for (String line : body.split("\n")) {
            if (line.isBlank()) {
                if (data != null) {
                    events.add(new SseEvent(name, data));
                }
                name = null;
                data = null;
                continue;
            }
            if (line.startsWith("event:")) {
                name = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                data = line.substring("data:".length()).trim();
            }
        }
        if (data != null) {
            events.add(new SseEvent(name, data));
        }
        return events;
    }

    private List<String> names(List<SseEvent> events) {
        return events.stream().map(SseEvent::name).toList();
    }

    private List<String> textDeltas(List<SseEvent> events) {
        List<String> texts = new ArrayList<>();
        for (SseEvent event : events) {
            if ("TEXT_DELTA".equals(event.name())) {
                texts.add(json(event).get("text").asText());
            }
        }
        return texts;
    }

    private JsonNode json(SseEvent event) {
        try {
            return objectMapper.readTree(event.json());
        } catch (IOException e) {
            throw new IllegalStateException("事件载荷不是合法 JSON：" + event.json(), e);
        }
    }
}
