package com.ragwiki.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragwiki.common.model.ChatModelSettings;
import com.ragwiki.common.model.ChatClient;
import com.ragwiki.common.model.ModelProperties;
import com.ragwiki.common.model.OpenAiChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 模型客户端装配。三组配置各建各的实例，互不牵连：
 * 在线问答用 chat，Wiki 离线编译用 compile，embedding 由 OpenAiEmbeddingClient 自己读配置。
 */
@Configuration
public class ModelClientConfig {

    @Bean
    @Primary
    public ChatClient chatClient(ModelProperties properties, ObjectMapper objectMapper) {
        ModelProperties.Chat chat = properties.chat();
        return new OpenAiChatClient(new ChatModelSettings(
                chat == null ? null : chat.baseUrl(),
                chat == null ? null : chat.apiKey(),
                chat == null ? null : chat.model(),
                chat == null ? null : chat.temperature(),
                chat == null ? null : chat.maxTokens(),
                chat == null ? null : chat.timeout()), objectMapper);
    }

    @Bean
    public ChatClient compileChatClient(ModelProperties properties, ObjectMapper objectMapper) {
        ModelProperties.Compile compile = properties.compile();
        return new OpenAiChatClient(new ChatModelSettings(
                compile == null ? null : compile.baseUrl(),
                compile == null ? null : compile.apiKey(),
                compile == null ? null : compile.model(),
                compile == null ? null : compile.temperature(),
                compile == null ? null : compile.maxTokens(),
                compile == null ? null : compile.timeout()), objectMapper);
    }
}
