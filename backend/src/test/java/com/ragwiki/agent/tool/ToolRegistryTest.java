package com.ragwiki.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具注册表：参数解析与校验、异常兜底。工具失败要变成结构化错误回给模型，不能中断主循环。
 */
class ToolRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 只声明入参、执行时原样回显，用来单独验注册表这一层 */
    private static Tool echoTool() {
        return new Tool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "回显参数";
            }

            @Override
            public Map<String, Object> parameterSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "orderNo", Map.of("type", "string"),
                                "count", Map.of("type", "integer")),
                        "required", List.of("orderNo"));
            }

            @Override
            public ToolOutcome execute(Map<String, Object> arguments, ToolContext context) {
                return ToolOutcome.ok("orderNo=" + arguments.get("orderNo"));
            }
        };
    }

    private ToolRegistry registry(Tool... tools) {
        return new ToolRegistry(List.of(tools), objectMapper);
    }

    @Test
    void exposesRegisteredToolsAsModelSpecs() {
        ToolRegistry registry = registry(echoTool());

        assertThat(registry.specs()).hasSize(1);
        assertThat(registry.specs().get(0).function().name()).isEqualTo("echo");
        assertThat(registry.find("echo")).isPresent();
        assertThat(registry.find("missing")).isEmpty();
    }

    @Test
    void rejectsDuplicateToolNames() {
        assertThatThrownBy(() -> registry(echoTool(), echoTool()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工具名重复");
    }

    @Test
    void invokesToolWithParsedArguments() {
        ToolOutcome outcome = registry(echoTool()).invoke(
                "echo", "{\"orderNo\": \"SO20250901001\"}", ToolContext.empty());

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.content()).isEqualTo("orderNo=SO20250901001");
    }

    @Test
    void unknownToolReturnsStructuredError() {
        ToolOutcome outcome = registry(echoTool()).invoke("missing", "{}", ToolContext.empty());

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.content()).contains("TOOL_NOT_FOUND").contains("echo");
    }

    @Test
    void malformedJsonReturnsStructuredError() {
        ToolOutcome outcome = registry(echoTool()).invoke("echo", "{不是 JSON", ToolContext.empty());

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.content()).contains("INVALID_ARGUMENTS");
    }

    @Test
    void missingRequiredArgumentReturnsExample() throws Exception {
        ToolOutcome outcome = registry(echoTool()).invoke("echo", "{}", ToolContext.empty());

        assertThat(outcome.success()).isFalse();
        // 报错要带上合法入参的形状，模型下一轮才能自己改对
        var payload = objectMapper.readTree(outcome.content());
        assertThat(payload.get("error").asText()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(payload.get("message").asText())
                .contains("缺少必填参数 orderNo")
                .contains("\"orderNo\":\"...\"");
    }

    @Test
    void blankRequiredValueCountsAsMissing() {
        ToolOutcome outcome = registry(echoTool()).invoke(
                "echo", "{\"orderNo\": \"   \"}", ToolContext.empty());

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.content()).contains("缺少必填参数 orderNo");
    }

    @Test
    void wrongArgumentTypeIsRejected() {
        ToolOutcome outcome = registry(echoTool()).invoke(
                "echo", "{\"orderNo\": \"SO1\", \"count\": \"很多\"}", ToolContext.empty());

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.content()).contains("参数 count 应为 integer");
    }

    @Test
    void toolExceptionBecomesStructuredErrorInsteadOfPropagating() {
        Tool throwing = new Tool() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public String description() {
                return "总是失败";
            }

            @Override
            public Map<String, Object> parameterSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public ToolOutcome execute(Map<String, Object> arguments, ToolContext context) {
                throw new IllegalStateException("下游超时");
            }
        };

        ToolOutcome outcome = registry(throwing).invoke("boom", "{}", ToolContext.empty());

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.content()).contains("TOOL_EXECUTION_FAILED").contains("下游超时");
    }

    @Test
    void blankArgumentsAreTreatedAsEmptyObject() {
        Tool noArg = new Tool() {
            @Override
            public String name() {
                return "ping";
            }

            @Override
            public String description() {
                return "无参";
            }

            @Override
            public Map<String, Object> parameterSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public ToolOutcome execute(Map<String, Object> arguments, ToolContext context) {
                return ToolOutcome.ok("pong:" + arguments.size());
            }
        };

        assertThat(registry(noArg).invoke("ping", null, ToolContext.empty()).content()).isEqualTo("pong:0");
        assertThat(registry(noArg).invoke("ping", "  ", ToolContext.empty()).content()).isEqualTo("pong:0");
    }
}
