package com.ragwiki.agent.protocol;

/**
 * 内核的输入指令。用 sealed 收敛类型，新增指令必须改这个接口。
 * 与知识层解耦：这里只表达「内核要做什么」，不出现 wiki、rag 的概念。
 */
public sealed interface Op {

    /** 用户发来一条输入，驱动一轮推进 */
    record UserInput(String sessionId, String text) implements Op {
    }
}
