package com.ragwiki.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragwiki.common.model.dto.ChatTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表。按名字查找，负责参数解析与校验、异常兜底。
 * 工具执行失败不往上抛，统一转成结构化错误回传给模型，避免中断主循环。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools;
    private final ObjectMapper objectMapper;

    public ToolRegistry(List<Tool> toolBeans, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        Map<String, Tool> registered = new LinkedHashMap<>();
        for (Tool tool : toolBeans) {
            Tool previous = registered.put(tool.name(), tool);
            if (previous != null) {
                throw new IllegalStateException("工具名重复：" + tool.name());
            }
        }
        this.tools = Map.copyOf(registered);
        log.info("已注册工具：{}", registered.keySet());
    }

    /** 提供给模型的工具声明 */
    public List<ChatTool> specs() {
        return tools.values().stream()
                .map(tool -> ChatTool.of(tool.name(), tool.description(), tool.parameterSchema()))
                .toList();
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 解析参数、校验、执行，任何失败都转成结构化错误。
     *
     * @param rawArguments 模型给出的参数，协议上是 JSON 字符串
     */
    public ToolOutcome invoke(String name, String rawArguments, ToolContext context) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return structuredError("TOOL_NOT_FOUND", "没有名为 " + name + " 的工具，可用工具：" + tools.keySet());
        }

        Map<String, Object> arguments;
        try {
            arguments = parseArguments(rawArguments);
        } catch (Exception e) {
            return structuredError("INVALID_ARGUMENTS", "参数不是合法 JSON 对象：" + e.getMessage());
        }

        String violation = validate(tool, arguments);
        if (violation != null) {
            return structuredError("INVALID_ARGUMENTS", violation);
        }

        try {
            return tool.execute(arguments, context);
        } catch (Exception e) {
            log.error("工具执行异常 name={} arguments={}", name, arguments, e);
            return structuredError("TOOL_EXECUTION_FAILED", "工具执行失败：" + e.getMessage());
        }
    }

    private Map<String, Object> parseArguments(String rawArguments) throws Exception {
        if (rawArguments == null || rawArguments.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(rawArguments, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    /** 按 schema 里的 required 与 type 做最小校验，返回第一条问题，全部通过返回 null */
    private String validate(Tool tool, Map<String, Object> arguments) {
        Object requiredNode = tool.parameterSchema().get("required");
        if (requiredNode instanceof List<?> required) {
            for (Object field : required) {
                Object value = arguments.get(String.valueOf(field));
                if (value == null || (value instanceof String text && text.isBlank())) {
                    return "缺少必填参数 " + field + "，正确入参形如 " + exampleArguments(tool);
                }
            }
        }

        Object propertiesNode = tool.parameterSchema().get("properties");
        if (propertiesNode instanceof Map<?, ?> properties) {
            for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                if (!(properties.get(entry.getKey()) instanceof Map<?, ?> spec)) {
                    continue;
                }
                Object expected = spec.get("type");
                if (expected != null && !matchesType(entry.getValue(), String.valueOf(expected))) {
                    return "参数 " + entry.getKey() + " 应为 " + expected + " 类型";
                }
            }
        }
        return null;
    }

    private boolean matchesType(Object value, String expected) {
        return switch (expected) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Integer || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            default -> true;
        };
    }

    private String exampleArguments(Tool tool) {
        Object requiredNode = tool.parameterSchema().get("required");
        if (!(requiredNode instanceof List<?> required) || required.isEmpty()) {
            return "{}";
        }
        List<String> pairs = new ArrayList<>();
        for (Object field : required) {
            pairs.add("\"" + field + "\":\"...\"");
        }
        return "{" + String.join(",", pairs) + "}";
    }

    private ToolOutcome structuredError(String error, String message) {
        try {
            return ToolOutcome.error(objectMapper.writeValueAsString(Map.of("error", error, "message", message)));
        } catch (Exception e) {
            return ToolOutcome.error("{\"error\":\"" + error + "\",\"message\":\"参数校验失败\"}");
        }
    }
}
