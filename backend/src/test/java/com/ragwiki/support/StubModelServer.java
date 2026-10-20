package com.ragwiki.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAI 兼容的桩上游，用来在没有真实模型密钥时验证整条链路。
 *
 * <p>只替换「模型输出」这一环，流式协议、SSE 解析、工具调用组装、落库、恢复都由被测代码真实执行。
 * 纯 JDK 实现，不引第三方依赖，既能在测试里起，也能单独跑着手工验收。
 *
 * <p>行为是确定性的，按请求体做判断：
 * <ul>
 *   <li>请求里已有 tool 角色消息 → 流式输出最终答复</li>
 *   <li>用户消息提到订单号 → 流式返回 query_order 的工具调用，参数分片下发</li>
 *   <li>其余 → 流式输出固定话术</li>
 *   <li>请求体含「触发模型错误」→ 返回 HTTP 500</li>
 *   <li>请求体含「无限工具调用」→ 一直返回工具调用，用来验证步数上限</li>
 * </ul>
 *
 * <p>单独运行：{@code java -cp <classes> com.ragwiki.support.StubModelServer 8090 20}
 */
public final class StubModelServer implements AutoCloseable {

    private static final Pattern ORDER_NO = Pattern.compile("SO\\d{6,}");
    private static final String CHAT_PATH = "/v1/chat/completions";
    private static final String EMBEDDINGS_PATH = "/v1/embeddings";

    /** 桩向量的维度，与配置里的 EMBEDDING_DIMENSIONS 对齐 */
    private static final int STUB_DIMENSIONS = 1024;

    private static final String TOOL_MARKER = "\"role\":\"tool\"";
    private static final String ERROR_TRIGGER = "触发模型错误";
    private static final String LOOP_TRIGGER = "无限工具调用";
    private static final String ORDER_TRIGGER = "订单";

    private static final String FALLBACK_ANSWER = "你好，我是售后客服助手。可以告诉我订单号，或者直接描述遇到的问题。";
    private static final String TOOL_ANSWER_PREFIX = "已经帮你查到这张订单：";

    /** 编译 / lint 的提示词里没有稳定可判的特征词，由测试按请求体里的标记登记固定回复 */
    private final Map<String, String> scriptedAnswers = new ConcurrentHashMap<>();

    private final HttpServer server;
    private final ExecutorService executor;
    private final int chunkDelayMillis;

    public StubModelServer() throws IOException {
        this(0, 0);
    }

    public StubModelServer(int port, int chunkDelayMillis) throws IOException {
        this.chunkDelayMillis = chunkDelayMillis;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext(CHAT_PATH, this::handleChat);
        this.server.createContext(EMBEDDINGS_PATH, this::handleEmbeddings);
        this.server.setExecutor(executor);
        this.server.start();
    }

    /** 模拟 OpenAI 风格的服务地址，客户端会拼上 /chat/completions 或 /embeddings */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handleChat(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        // 只按用户说的话判断。整个请求体里还有系统提示和工具描述，它们本来就含「订单」这类词，扫全文会误判
        String userText = lastMessageContent(body, "user");

        if (userText.contains(ERROR_TRIGGER)) {
            byte[] payload = "{\"error\":{\"message\":\"stub 主动返回的错误\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(500, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
            return;
        }

        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            emitPlan(out, body, userText);
            out.flush();
        }
    }

    /**
     * 登记一条按标记匹配的固定回复。编译与 lint 的提示词里没有可稳定识别的业务词，
     * 由测试把源文档标题、条目标题这类标记登记进来，命中即返回约定的 JSON。
     */
    public void script(String marker, String reply) {
        scriptedAnswers.put(marker, reply);
    }

    private void emitPlan(OutputStream out, String body, String userText) throws IOException {
        for (Map.Entry<String, String> entry : scriptedAnswers.entrySet()) {
            if (body.contains(entry.getKey())) {
                emitText(out, entry.getValue());
                return;
            }
        }
        if (userText.contains(LOOP_TRIGGER)) {
            emitToolCall(out, "SO20250901001");
            return;
        }
        if (body.contains(TOOL_MARKER)) {
            emitText(out, TOOL_ANSWER_PREFIX + lastToolContent(body));
            return;
        }
        Matcher matcher = ORDER_NO.matcher(userText);
        if (userText.contains(ORDER_TRIGGER) && matcher.find()) {
            emitToolCall(out, matcher.group());
            return;
        }
        emitText(out, FALLBACK_ANSWER);
    }

    /** 取指定角色最后一条消息的 content */
    private String lastMessageContent(String body, String role) {
        int index = body.lastIndexOf("\"role\":\"" + role + "\"");
        if (index < 0) {
            return "";
        }
        return extractContent(body, index);
    }

    /** 从请求里粗略取出最后一条 tool 消息的内容，用来构造最终答复 */
    private String lastToolContent(String body) {
        int index = body.lastIndexOf("\"role\":\"tool\"");
        if (index < 0) {
            return "";
        }
        return extractContent(body, index).replace("\n", "；").trim();
    }

    private String extractContent(String body, int fromIndex) {
        int contentIndex = body.indexOf("\"content\":\"", fromIndex);
        if (contentIndex < 0) {
            return "";
        }
        int start = contentIndex + "\"content\":\"".length();
        StringBuilder text = new StringBuilder();
        for (int i = start; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char next = body.charAt(++i);
                text.append(next == 'n' ? '\n' : next == 't' ? '\t' : next);
                continue;
            }
            if (c == '"') {
                break;
            }
            text.append(c);
        }
        return text.toString();
    }

    // ---------- embeddings ----------

    /**
     * 桩向量：按字符与相邻双字哈希到固定维度再 L2 归一化。
     * 词汇重叠多的文本向量更近，所以向量那一路在集成测试里能跑出真实行为，
     * 但它的语义能力远不如真模型——语义质量的验收必须用真 embedding 端点。
     */
    private void handleEmbeddings(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        List<String> inputs = extractInputs(body);

        StringBuilder json = new StringBuilder("{\"object\":\"list\",\"model\":\"stub-embedding\",\"data\":[");
        for (int i = 0; i < inputs.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            float[] vector = embed(inputs.get(i));
            json.append("{\"object\":\"embedding\",\"index\":").append(i).append(",\"embedding\":[");
            for (int j = 0; j < vector.length; j++) {
                if (j > 0) {
                    json.append(',');
                }
                json.append(vector[j]);
            }
            json.append("]}");
        }
        json.append("],\"usage\":{\"prompt_tokens\":0,\"total_tokens\":0}}");

        byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private float[] embed(String text) {
        float[] vector = new float[STUB_DIMENSIONS];
        String normalized = text.strip().toLowerCase();
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if (Character.isWhitespace(current)) {
                continue;
            }
            bump(vector, String.valueOf(current));
            if (i + 1 < normalized.length() && !Character.isWhitespace(normalized.charAt(i + 1))) {
                bump(vector, normalized.substring(i, i + 2));
            }
        }

        double squareSum = 0;
        for (int i = 0; i < vector.length; i++) {
            if (vector[i] > 0) {
                // sublinear tf：不做的话单个高频桶会主导整条向量，
                // 无关文本之间靠哈希碰撞也能凑出很高的余弦
                vector[i] = (float) (1 + Math.log(vector[i]));
                squareSum += vector[i] * vector[i];
            }
        }
        if (squareSum > 0) {
            float inverse = (float) (1.0 / Math.sqrt(squareSum));
            for (int i = 0; i < vector.length; i++) {
                vector[i] *= inverse;
            }
        }
        return vector;
    }

    private void bump(float[] vector, String token) {
        vector[Math.floorMod(hash(token), vector.length)] += 1f;
    }

    /**
     * FNV-1a 加一轮终混合。
     * 直接用 String.hashCode 的话，英文字母与标点的哈希值挤在连续桶里，
     * 与中文语料的桶大量重合，余弦相似度就失真了。
     */
    private static int hash(String token) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < token.length(); i++) {
            hash ^= token.charAt(i);
            hash *= 0x01000193;
        }
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        hash ^= hash >>> 15;
        return hash;
    }

    /** 从请求体里取出 input，支持单个字符串与字符串数组两种写法 */
    private List<String> extractInputs(String body) {
        int key = body.indexOf("\"input\"");
        if (key < 0) {
            return List.of();
        }
        int cursor = body.indexOf(':', key);
        if (cursor < 0) {
            return List.of();
        }
        cursor++;
        while (cursor < body.length() && Character.isWhitespace(body.charAt(cursor))) {
            cursor++;
        }

        List<String> inputs = new ArrayList<>();
        if (cursor < body.length() && body.charAt(cursor) == '[') {
            cursor++;
            while (cursor < body.length()) {
                while (cursor < body.length()
                        && (Character.isWhitespace(body.charAt(cursor)) || body.charAt(cursor) == ',')) {
                    cursor++;
                }
                if (cursor >= body.length() || body.charAt(cursor) == ']') {
                    break;
                }
                if (body.charAt(cursor) == '"') {
                    StringBuilder value = new StringBuilder();
                    cursor = readJsonString(body, cursor, value);
                    inputs.add(value.toString());
                } else {
                    cursor++;
                }
            }
        } else if (cursor < body.length() && body.charAt(cursor) == '"') {
            StringBuilder value = new StringBuilder();
            readJsonString(body, cursor, value);
            inputs.add(value.toString());
        }
        return inputs;
    }

    /** 从 body 的起始引号读一个 JSON 字符串，返回结束引号之后的游标 */
    private int readJsonString(String body, int quoteIndex, StringBuilder out) {
        int cursor = quoteIndex + 1;
        while (cursor < body.length()) {
            char c = body.charAt(cursor);
            if (c == '\\' && cursor + 1 < body.length()) {
                char next = body.charAt(cursor + 1);
                out.append(next == 'n' ? '\n' : next == 't' ? '\t' : next);
                cursor += 2;
                continue;
            }
            if (c == '"') {
                return cursor + 1;
            }
            out.append(c);
            cursor++;
        }
        return cursor;
    }

    private void emitText(OutputStream out, String text) throws IOException {
        int chunkSize = 4;
        for (int i = 0; i < text.length(); i += chunkSize) {
            String chunk = text.substring(i, Math.min(text.length(), i + chunkSize));
            writeData(out, "{\"id\":\"stub\",\"object\":\"chat.completion.chunk\",\"choices\":"
                    + "[{\"index\":0,\"delta\":{\"content\":\"" + escape(chunk) + "\"},\"finish_reason\":null}]}");
            pause();
        }
        writeData(out, "{\"id\":\"stub\",\"object\":\"chat.completion.chunk\",\"choices\":"
                + "[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");
        writeDone(out);
    }

    /** 参数分片下发，模拟真实服务端的 tool_calls 增量，验证客户端按 index 重组 */
    private void emitToolCall(OutputStream out, String orderNo) throws IOException {
        String arguments = "{\"orderNo\":\"" + orderNo + "\"}";
        writeData(out, "{\"id\":\"stub\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":"
                + "{\"tool_calls\":[{\"index\":0,\"id\":\"call_stub_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"query_order\",\"arguments\":\"\"}}]},\"finish_reason\":null}]}");
        pause();
        int split = arguments.length() / 2;
        for (String fragment : new String[]{arguments.substring(0, split), arguments.substring(split)}) {
            writeData(out, "{\"id\":\"stub\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":"
                    + "{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"" + escape(fragment) + "\"}}]},"
                    + "\"finish_reason\":null}]}");
            pause();
        }
        writeData(out, "{\"id\":\"stub\",\"object\":\"chat.completion.chunk\",\"choices\":"
                + "[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
        writeDone(out);
    }

    private void writeData(OutputStream out, String json) throws IOException {
        out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void writeDone(OutputStream out) throws IOException {
        out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void pause() {
        if (chunkDelayMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(chunkDelayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8090;
        int delay = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        StubModelServer server = new StubModelServer(port, delay);
        System.out.println("桩模型服务已启动：" + server.baseUrl() + "  分片间隔 " + delay + "ms");
        Thread.currentThread().join();
    }
}
