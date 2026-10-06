package com.ragwiki.agent.store;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragwiki.agent.entity.AgentEvent;
import com.ragwiki.agent.mapper.AgentEventMapper;
import com.ragwiki.agent.protocol.Event;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 事件 append-only 存储。只插入不更新，是会话恢复的事实来源。
 */
@Component
public class EventStore {

    private final AgentEventMapper agentEventMapper;
    private final ObjectMapper objectMapper;

    public EventStore(AgentEventMapper agentEventMapper, ObjectMapper objectMapper) {
        this.agentEventMapper = agentEventMapper;
        this.objectMapper = objectMapper;
    }

    /** 取下一个可用的事件序号。一轮开始时取一次，之后在内存里自增，避免每条事件都查库 */
    public int nextSeq(long conversationId) {
        return agentEventMapper.nextSeq(conversationId);
    }

    public void append(long conversationId, int seq, Event event) {
        AgentEvent record = new AgentEvent();
        record.setConversationId(conversationId);
        record.setSeq(seq);
        record.setEventType(event.type().name());
        record.setPayload(writePayload(event));
        record.setCreatedAt(Instant.now());
        agentEventMapper.insert(record);
    }

    /**
     * 按顺序回放出一轮对话的全部事件，用于恢复会话上下文。
     * 任一载荷损坏即整段失败：跳过坏事件会让上下文与真实历史对不上（比如少一条工具结果），
     * 宁可让调用方按空上下文重来。
     */
    public List<Event> replay(long conversationId) {
        List<AgentEvent> records = agentEventMapper.selectList(Wrappers.<AgentEvent>lambdaQuery()
                .eq(AgentEvent::getConversationId, conversationId)
                .orderByAsc(AgentEvent::getSeq));

        List<Event> events = new ArrayList<>(records.size());
        for (AgentEvent record : records) {
            try {
                events.add(objectMapper.readValue(record.getPayload(), Event.class));
            } catch (Exception e) {
                throw new IllegalStateException(
                        "事件载荷无法反序列化 conversationId=" + conversationId + " seq=" + record.getSeq(), e);
            }
        }
        return events;
    }

    private String writePayload(Event event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("事件载荷无法序列化 " + event.type(), e);
        }
    }
}
