package com.ragwiki.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档配置。接口级的 @Operation / @Schema 注解在各 controller 上补充。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ragWikiOpenApi() {
        return new OpenAPI().info(new Info()
                .title("RAG-Wiki API")
                .version("v1")
                .description("电商售后 Agent：LLM Wiki 快通道 + RAG 慢通道双通道知识问答"));
    }
}
