package com.ragwiki.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Elasticsearch 客户端装配。地址与超时取 spring.elasticsearch.*。
 * 索引与别名的创建、校验放在 rag 模块，这里只负责把客户端接起来。
 */
@Configuration
public class ElasticsearchConfig {

    @Bean(destroyMethod = "close")
    public RestClient elasticsearchRestClient(
            @Value("${spring.elasticsearch.uris}") List<String> uris,
            @Value("${spring.elasticsearch.connection-timeout:5s}") Duration connectionTimeout,
            @Value("${spring.elasticsearch.socket-timeout:30s}") Duration socketTimeout) {

        HttpHost[] hosts = uris.stream()
                .map(ElasticsearchConfig::toHttpHost)
                .toArray(HttpHost[]::new);

        return RestClient.builder(hosts)
                .setRequestConfigCallback(config -> config
                        .setConnectTimeout((int) connectionTimeout.toMillis())
                        .setSocketTimeout((int) socketTimeout.toMillis()))
                .build();
    }

    @Bean(destroyMethod = "close")
    public RestClientTransport elasticsearchTransport(RestClient restClient) {
        // ES 响应里字段比我们的记录类型多，关掉未知字段报错，避免每加一个字段就得改代码
        ObjectMapper objectMapper = JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
        return new RestClientTransport(restClient, new JacksonJsonpMapper(objectMapper));
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClientTransport transport) {
        return new ElasticsearchClient(transport);
    }

    private static HttpHost toHttpHost(String uri) {
        URI parsed = URI.create(uri);
        return new HttpHost(parsed.getHost(), parsed.getPort(), parsed.getScheme());
    }
}
