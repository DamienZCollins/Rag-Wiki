package com.ragwiki.agent.loop;

import com.ragwiki.agent.protocol.Event;

/**
 * 事件出口。内核只认这个接口，SSE 推送、测试收集、以后可能的其他消费方都实现它。
 */
@FunctionalInterface
public interface EventSink {

    void emit(Event event);
}
