package com.ragwiki.agent.loop;

/**
 * 客户端已断开。由 EventSink 在写不出去时抛出，用来打断内核循环并释放虚拟线程。
 */
public class ClientDisconnectedException extends RuntimeException {

    public ClientDisconnectedException(String message) {
        super(message);
    }
}
