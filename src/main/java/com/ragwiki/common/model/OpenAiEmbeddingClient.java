package com.ragwiki.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ragwiki.common.exception.BusinessException;
import com.ragwiki.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * OpenAI 兼容的 embeddings 客户端。索引与查询共用同一个实例，保证语义空间一致。
 * base-url 在调用时才解析，密钥没配好也能正常启动应用。
 */
@Component
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingClient.class);
    private static final int ERROR_BODY_LIMIT = 500;

    private final ModelProperties properties;
    private final RestClient restClient;

    public OpenAiEmbeddingClient(ModelProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory(properties.embedding()))
                .build();
    }

    private static SimpleClientHttpRequestFactory requestFactory(ModelProperties.Embedding embedding) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        Duration timeout = embedding == null || embedding.timeout() == null
                ? Duration.ofSeconds(60) : embedding.timeout();
        factory.setReadTimeout(timeout);
        return factory;
    }

    @Override
    public int dimensions() {
        ModelProperties.Embedding embedding = properties.embedding();
        if (embedding == null || embedding.dimensions() == null) {
            throw new BusinessException(ResultCode.MODEL_NOT_CONFIGURED,
                    "model.embedding.dimensions 未配置，检查 .env 的 EMBEDDING_DIMENSIONS");
        }
        return embedding.dimensions();
    }

    @Override
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        ModelProperties.Embedding embedding = requireConfigured();
        int batchSize = embedding.batchSize() == null || embedding.batchSize() <= 0
                ? texts.size() : embedding.batchSize();

        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            List<String> slice = texts.subList(start, Math.min(texts.size(), start + batchSize));
            vectors.addAll(requestBatch(embedding, slice));
        }
        return vectors;
    }

    private List<float[]> requestBatch(ModelProperties.Embedding embedding, List<String> slice) {
        String url = trimTrailingSlash(embedding.baseUrl()) + "/embeddings";
        EmbeddingRequest body = new EmbeddingRequest(embedding.model(), slice);

        EmbeddingResponse response;
        try {
            response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + embedding.apiKey())
                    .body(body)
                    .retrieve()
                    .body(EmbeddingResponse.class);
        } catch (BusinessException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw translateAccessException(url, e);
        } catch (RestClientResponseException e) {
            throw translateHttpError(e.getStatusCode(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new BusinessException(ResultCode.MODEL_RESPONSE_INVALID,
                    "解析 embedding 响应失败：" + e.getMessage());
        }

        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new BusinessException(ResultCode.MODEL_RESPONSE_INVALID, "embedding 响应里没有向量数据");
        }
        // 服务端不保证按入参顺序返回，按 index 对齐
        List<EmbeddingResponse.Item> items = new ArrayList<>(response.data());
        items.sort(Comparator.comparingInt(EmbeddingResponse.Item::index));
        if (items.size() != slice.size()) {
            throw new BusinessException(ResultCode.MODEL_RESPONSE_INVALID,
                    "embedding 返回条数 %d 与入参 %d 不一致".formatted(items.size(), slice.size()));
        }

        int expected = dimensions();
        List<float[]> vectors = new ArrayList<>(items.size());
        for (EmbeddingResponse.Item item : items) {
            List<Float> values = item.embedding();
            if (values == null || values.size() != expected) {
                throw new BusinessException(ResultCode.MODEL_RESPONSE_INVALID,
                        "embedding 维度与 EMBEDDING_DIMENSIONS 不一致：返回 %s，配置 %d。改维度必须同时重建索引"
                                .formatted(values == null ? "null" : values.size(), expected));
            }
            float[] vector = new float[values.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = values.get(i);
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private ModelProperties.Embedding requireConfigured() {
        ModelProperties.Embedding embedding = properties.embedding();
        if (embedding == null
                || !StringUtils.hasText(embedding.baseUrl())
                || !StringUtils.hasText(embedding.apiKey())
                || !StringUtils.hasText(embedding.model())) {
            throw new BusinessException(ResultCode.MODEL_NOT_CONFIGURED,
                    "model.embedding 的 base-url / api-key / model 未配置完整，检查 .env 与 config/application.yml");
        }
        return embedding;
    }

    private BusinessException translateHttpError(HttpStatusCode status, String body) {
        ResultCode code = switch (status.value()) {
            case 401, 403 -> ResultCode.MODEL_AUTH_FAILED;
            case 429 -> ResultCode.MODEL_RATE_LIMITED;
            case 408, 504 -> ResultCode.MODEL_TIMEOUT;
            default -> ResultCode.MODEL_UNAVAILABLE;
        };
        return new BusinessException(code, "embedding 服务返回 HTTP " + status.value() + "：" + abbreviate(body));
    }

    private BusinessException translateAccessException(String url, ResourceAccessException e) {
        Throwable cause = e.getCause();
        if (cause instanceof SocketTimeoutException) {
            return new BusinessException(ResultCode.MODEL_TIMEOUT, "embedding 调用超时：" + url);
        }
        if (cause instanceof ConnectException) {
            return new BusinessException(ResultCode.MODEL_UNAVAILABLE, "连不上 embedding 服务：" + url);
        }
        return new BusinessException(ResultCode.MODEL_UNAVAILABLE,
                "embedding 调用失败：" + url + "：" + e.getMessage());
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= ERROR_BODY_LIMIT ? text : text.substring(0, ERROR_BODY_LIMIT) + "...";
    }

    record EmbeddingRequest(String model, List<String> input) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingResponse(List<Item> data, Usage usage) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Item(int index, List<Float> embedding) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Usage(@JsonProperty("prompt_tokens") Integer promptTokens,
                     @JsonProperty("total_tokens") Integer totalTokens) {
        }
    }
}
