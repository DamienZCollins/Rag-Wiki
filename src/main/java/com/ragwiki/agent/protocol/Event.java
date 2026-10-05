package com.ragwiki.agent.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 内核的输出事件。用 sealed 收敛类型，新增事件必须改这个接口。
 * 与知识层解耦：这里只表达「内核发生了什么」，不出现 wiki、rag 的概念。
 *
 * <p>同一套定义同时供 SSE 推送与事件持久化使用，SSE 的事件名取 {@link EventType} 的名字。
 * 事件载荷带 type 判别字段，可以直接反序列化回具体类型。
 *
 * <p>流以 {@link Finished} 作为唯一终止事件。中途出错时先发 {@link Error} 再发
 * {@code Finished(FAILED)}，客户端只需要认一个终止信号。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Event.Started.class, name = "STARTED"),
        @JsonSubTypes.Type(value = Event.Thinking.class, name = "THINKING"),
        @JsonSubTypes.Type(value = Event.TextDelta.class, name = "TEXT_DELTA"),
        @JsonSubTypes.Type(value = Event.ToolCall.class, name = "TOOL_CALL"),
        @JsonSubTypes.Type(value = Event.ToolResult.class, name = "TOOL_RESULT"),
        @JsonSubTypes.Type(value = Event.Finished.class, name = "FINISHED"),
        @JsonSubTypes.Type(value = Event.Error.class, name = "ERROR")
})
public sealed interface Event {

    EventType type();

    enum EventType {
        STARTED,
        THINKING,
        TEXT_DELTA,
        TOOL_CALL,
        TOOL_RESULT,
        FINISHED,
        ERROR
    }

    enum FinishReason {
        /** 模型给出最终答复，正常结束 */
        COMPLETED,
        /** 内层循环达到步数上限，仍有工具调用未收敛 */
        MAX_STEPS,
        /** 客户端断开，生成被中断 */
        CANCELLED,
        /** 出错终止 */
        FAILED
    }

    /** 一轮开始，携带本轮的会话标识与用户输入。会话恢复时靠它还原用户消息 */
    record Started(String sessionId, String userMessage) implements Event {
        @Override
        public EventType type() {
            return EventType.STARTED;
        }
    }

    /** 已发出请求、等待模型首个分片 */
    record Thinking() implements Event {
        @Override
        public EventType type() {
            return EventType.THINKING;
        }
    }

    /** 模型文本增量 */
    record TextDelta(String text) implements Event {
        @Override
        public EventType type() {
            return EventType.TEXT_DELTA;
        }
    }

    /** 模型请求调用工具 */
    record ToolCall(String callId, String name, String arguments) implements Event {
        @Override
        public EventType type() {
            return EventType.TOOL_CALL;
        }
    }

    /** 工具执行结果，失败也走这里，由 success 区分 */
    record ToolResult(String callId, String name, boolean success, String content) implements Event {
        @Override
        public EventType type() {
            return EventType.TOOL_RESULT;
        }
    }

    /** 终止事件，携带终止原因 */
    record Finished(FinishReason reason) implements Event {
        @Override
        public EventType type() {
            return EventType.FINISHED;
        }
    }

    record Error(String code, String message) implements Event {
        @Override
        public EventType type() {
            return EventType.ERROR;
        }
    }
}
