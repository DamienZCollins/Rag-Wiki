package com.ragwiki.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
    private static final String PATH = "/v1/chat/completions";

    private static final String TOOL_MARKER = "\"role\":\"tool\"";
    private static final String ERROR_TRIGGER = "触发模型错误";
    private static final String LOOP_TRIGGER = "无限工具调用";
    private static final String ORDER_TRIGGER = "订单";

    private static final String FALLBACK_ANSWER = "你好，我是售后客服助手。可以告诉我订单号，或者直接描述遇到的问题。";
    private static final String TOOL_ANSWER_PREFIX = "已经帮你查到这张订单：";

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
        this.server.createContext(PATH, this::handle);
        this.server.setExecutor(executor);
        this.server.start();
    }

    /** 模拟 OpenAI 风格的服务地址，客户端会拼上 /chat/completions */
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

    private void handle(HttpExchange exchange) throws IOException {
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

    private void emitPlan(OutputStream out, String body, String userText) throws IOException {
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
