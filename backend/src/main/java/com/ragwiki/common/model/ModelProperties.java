package com.ragwiki.common.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 模型接入配置，对应 config/application.yml 的 model.* 三组。
 * 分组隔离：三组可分别换模型、分别限额、分别排查，配置前缀不带模块名。
 */
@ConfigurationProperties(prefix = "model")
public record ModelProperties(Chat chat, Embedding embedding, Compile compile) {

    public record Chat(
            String baseUrl,
            String apiKey,
            String model,
            Double temperature,
            Integer maxTokens,
            Duration timeout) {
    }

    public record Embedding(
            String baseUrl,
            String apiKey,
            String model,
            Integer dimensions,
            Integer batchSize,
            Duration timeout) {
    }

    public record Compile(
            String baseUrl,
            String apiKey,
            String model,
            Double temperature,
            Integer maxTokens,
            Duration timeout) {
    }
}
