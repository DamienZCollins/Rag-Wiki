package com.ragwiki.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Agent 内核配置，对应 config/application.yml 的 agent.*
 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(

        /** 内层循环单轮的模型调用步数上限，防止工具调用不收敛 */
        @DefaultValue("6") Integer maxSteps,

        /** SSE 连接超时 */
        @DefaultValue("180s") Duration sseTimeout,

        /** 恢复会话上下文时最多带多少条历史消息 */
        @DefaultValue("40") Integer maxHistoryMessages) {
}
