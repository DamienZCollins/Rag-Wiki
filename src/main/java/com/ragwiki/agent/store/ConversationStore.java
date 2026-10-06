package com.ragwiki.agent.store;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ragwiki.agent.entity.Conversation;
import com.ragwiki.agent.entity.Message;
import com.ragwiki.agent.mapper.ConversationMapper;
import com.ragwiki.agent.mapper.MessageMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 会话与消息读写。
 * 写入失败由调用方兜底，这里不做吞异常处理，避免把「没写进去」和「写进去了」混为一谈。
 */
@Component
public class ConversationStore {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final int TITLE_MAX_LENGTH = 200;

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    public ConversationStore(ConversationMapper conversationMapper, MessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    /** sessionId 已存在则复用，否则新建 */
    public Conversation getOrCreate(String sessionId, String firstMessage) {
        Conversation existing = findBySessionId(sessionId);
        if (existing != null) {
            return existing;
        }

        Conversation created = new Conversation();
        created.setSessionId(sessionId);
        created.setStatus(STATUS_ACTIVE);
        created.setTitle(abbreviate(firstMessage));
        Instant now = Instant.now();
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        try {
            conversationMapper.insert(created);
            return created;
        } catch (DuplicateKeyException e) {
            // 并发下另一个请求先建好了，回查一次复用
            Conversation concurrent = findBySessionId(sessionId);
            if (concurrent == null) {
                throw e;
            }
            return concurrent;
        }
    }

    private Conversation findBySessionId(String sessionId) {
        return conversationMapper.selectOne(
                Wrappers.<Conversation>lambdaQuery().eq(Conversation::getSessionId, sessionId));
    }

    /** 追加一条消息，序号在会话内连续递增 */
    public void appendMessage(long conversationId, String role, String content,
                              String toolCallsJson, String toolCallId) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setSeq(messageMapper.nextSeq(conversationId));
        message.setRole(role);
        message.setContent(content);
        message.setToolCalls(toolCallsJson);
        message.setToolCallId(toolCallId);
        message.setCreatedAt(Instant.now());
        messageMapper.insert(message);

        Conversation touch = new Conversation();
        touch.setId(conversationId);
        touch.setUpdatedAt(message.getCreatedAt());
        conversationMapper.updateById(touch);
    }

    public List<Message> listMessages(long conversationId) {
        return messageMapper.selectList(Wrappers.<Message>lambdaQuery()
                .eq(Message::getConversationId, conversationId)
                .orderByAsc(Message::getSeq));
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= TITLE_MAX_LENGTH ? text : text.substring(0, TITLE_MAX_LENGTH);
    }
}
