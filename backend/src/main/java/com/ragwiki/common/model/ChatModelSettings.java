package com.ragwiki.common.model;

import java.time.Duration;

/**
 * 一组对话模型配置。三组（chat / embedding / compile）可以分别实例化客户端，
 * 换模型、限额、排查互不牵连。
 */
public record ChatModelSettings(
        String baseUrl,
        String apiKey,
        String model,
        Double temperature,
        Integer maxTokens,
        Duration timeout) {
}
