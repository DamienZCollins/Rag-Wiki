package com.ragwiki.agent.loop;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同一会话同一时刻只放行一轮对话。
 *
 * <p>会话内的消息与事件序号按当前最大值 +1 分配，两轮并发会撞 uk_*_conversation_seq 唯一键；
 * 事件日志是会话恢复的事实来源，丢一条就恢复不回来，所以并发的第二个请求直接失败，不排队。
 *
 * <p>锁放在进程内存里，只覆盖单实例部署；多实例要换成按会话的分布式锁。
 */
@Component
public class SessionTurnLock {

    private final Set<String> runningSessions = ConcurrentHashMap.newKeySet();

    /** 拿到返回 true，已有回合在跑返回 false */
    public boolean tryAcquire(String sessionId) {
        return runningSessions.add(sessionId);
    }

    public void release(String sessionId) {
        runningSessions.remove(sessionId);
    }
}
