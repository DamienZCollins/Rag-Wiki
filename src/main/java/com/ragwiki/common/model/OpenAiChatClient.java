package com.ragwiki.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragwiki.common.exception.BusinessException;
import com.ragwiki.common.model.dto.ChatCompletion;
import com.ragwiki.common.model.dto.ChatMessage;
import com.ragwiki.common.model.dto.ChatTool;
import com.ragwiki.common.model.dto.ToolCallRequest;
import com.ragwiki.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI 兼容的对话客户端，走 POST /chat/completions 的流式接口。
 *
 * <p>不是 Spring Bean，由装配层按配置组各建一个实例：
 * 在线问答用 model.chat，Wiki 离线编译用 model.compile。
 * base-url 在调用时才解析，密钥还没配好时也能正常启动应用。
 */
public class OpenAiChatClient implements ChatClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatClient.class);
    private static final String DATA_PREFIX = "data:";
    private static final String DONE = "[DONE]";
    private static final int ERROR_BODY_LIMIT = 500;

    private final ChatModelSettings settings;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public OpenAiChatClient(ChatModelSettings settings, ObjectMapper objectMapper) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().requestFactory(requestFactory(settings)).build();
    }

    private static SimpleClientHttpRequestFactory requestFactory(ChatModelSettings settings) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        Duration timeout = settings == null || settings.timeout() == null
                ? Duration.ofSeconds(60) : settings.timeout();
        factory.setReadTimeout(timeout);
        return factory;
    }

    @Override
    public ChatCompletion stream(List<ChatMessage> messages, List<ChatTool> tools, Consumer<String> onTextDelta) {
        ChatModelSettings configured = requireConfigured();
        String url = trimTrailingSlash(configured.baseUrl()) + "/chat/completions";
        ChatCompletionRequest body = new ChatCompletionRequest(
                configured.model(), messages, tools, configured.temperature(), configured.maxTokens());

        try {
            return restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + configured.apiKey())
                    .body(body)
                    .exchange((request, response) -> readStream(url, response, onTextDelta));
        } catch (BusinessException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw translateAccessException(url, e);
        } catch (RestClientResponseException e) {
            throw translateHttpError(e.getStatusCode(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new BusinessException(ResultCode.MODEL_RESPONSE_INVALID,
                    "解析模型响应失败：" + e.getMessage());
        }
    }

    private ChatModelSettings requireConfigured() {
        if (settings == null
                || !StringUtils.hasText(settings.baseUrl())
                || !StringUtils.hasText(settings.apiKey())
                || !StringUtils.hasText(settings.model())) {
            throw new BusinessException(ResultCode.MODEL_NOT_CONFIGURED,
                    "模型配置的 base-url / api-key / model 不完整，检查 .env 与 config/application.yml");
        }
        return settings;
    }

    private ChatCompletion readStream(String url,
                                      RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
                                      Consumer<String> onTextDelta) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.isError()) {
            throw translateHttpError(status, readErrorBody(response));
        }

        StringBuilder content = new StringBuilder();
        Map<Integer, PartialToolCall> toolCalls = new LinkedHashMap<>();
        String finishReason = null;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(DATA_PREFIX)) {
                    continue;
                }
                String data = line.substring(DATA_PREFIX.length()).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if (DONE.equals(data)) {
                    break;
                }
                finishReason = handleChunk(data, content, toolCalls, onTextDelta);
            }
        }

        log.debug("模型流式响应结束 url={} contentLength={} toolCalls={} finishReason={}",
                url, content.length(), toolCalls.size(), finishReason);
        return new ChatCompletion(
                content.isEmpty() ? null : content.toString(),
                toToolCalls(toolCalls),
                finishReason);
    }

    /** 处理一个分片，返回其中携带的 finish_reason（没有则返回 null） */
    private String handleChunk(String data,
                               StringBuilder content,
                               Map<Integer, PartialToolCall> toolCalls,
                               Consumer<String> onTextDelta) {
        JsonNode chunk;
        try {
            chunk = objectMapper.readTree(data);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.MODEL_RESPONSE_INVALID,
                    "模型分片不是合法 JSON：" + abbreviate(data));
        }

        JsonNode choices = chunk.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode choice = choices.get(0);

        JsonNode delta = choice.path("delta");
        JsonNode textNode = delta.get("content");
        if (textNode != null && !textNode.isNull()) {
            String text = textNode.asText();
            if (!text.isEmpty()) {
                content.append(text);
                onTextDelta.accept(text);
            }
        }

        // 工具调用是分片下发的：首片带 id 与 name，后续片只追加 arguments，按 index 归并
        JsonNode calls = delta.get("tool_calls");
        if (calls != null && calls.isArray()) {
            for (JsonNode call : calls) {
                PartialToolCall partial = toolCalls.computeIfAbsent(
                        call.path("index").asInt(0), index -> new PartialToolCall());
                if (call.hasNonNull("id")) {
                    partial.id = call.get("id").asText();
                }
                JsonNode function = call.get("function");
                if (function != null) {
                    if (function.hasNonNull("name")) {
                        partial.name = function.get("name").asText();
                    }
                    if (function.hasNonNull("arguments")) {
                        partial.arguments.append(function.get("arguments").asText());
                    }
                }
            }
        }

        return choice.hasNonNull("finish_reason") ? choice.get("finish_reason").asText() : null;
    }

    private List<ToolCallRequest> toToolCalls(Map<Integer, PartialToolCall> partials) {
        if (partials.isEmpty()) {
            return List.of();
        }
        List<ToolCallRequest> result = new ArrayList<>(partials.size());
        partials.forEach((index, partial) -> result.add(
                ToolCallRequest.of(partial.id, partial.name, partial.arguments.toString())));
        return result;
    }

    private BusinessException translateHttpError(HttpStatusCode status, String body) {
        ResultCode code = switch (status.value()) {
            case 401, 403 -> ResultCode.MODEL_AUTH_FAILED;
            case 429 -> ResultCode.MODEL_RATE_LIMITED;
            case 408, 504 -> ResultCode.MODEL_TIMEOUT;
            default -> ResultCode.MODEL_UNAVAILABLE;
        };
        return new BusinessException(code,
                "模型返回 HTTP " + status.value() + "：" + abbreviate(body));
    }

    private BusinessException translateAccessException(String url, ResourceAccessException e) {
        Throwable cause = e.getCause();
        if (cause instanceof SocketTimeoutException) {
            return new BusinessException(ResultCode.MODEL_TIMEOUT, "模型调用超时：" + url);
        }
        if (cause instanceof ConnectException) {
            return new BusinessException(ResultCode.MODEL_UNAVAILABLE, "连不上模型服务：" + url);
        }
        return new BusinessException(ResultCode.MODEL_UNAVAILABLE,
                "模型调用失败：" + url + "：" + e.getMessage());
    }

    private String readErrorBody(
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try (var body = response.getBody()) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<读取响应体失败>";
        }
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

    /** 流式过程中按 index 累积的工具调用片段 */
    private static final class PartialToolCall {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ChatCompletionRequest(
            String model,
            List<ChatMessage> messages,
            List<ChatTool> tools,
            Double temperature,
            @JsonProperty("max_tokens") Integer maxTokens,
            boolean stream) {

        ChatCompletionRequest(String model, List<ChatMessage> messages, List<ChatTool> tools,
                              Double temperature, Integer maxTokens) {
            this(model, messages, tools, temperature, maxTokens, true);
        }
    }
}
