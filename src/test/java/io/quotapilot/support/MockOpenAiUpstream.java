package io.quotapilot.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * [测试支撑] 内嵌 OpenAI 兼容上游（真实 HTTP + SSE）：
 * - /v1/chat/completions 按 stream_options.include_usage 语义发送 SSE 块与 usage 终块；
 * - 故障模式：CLIENT_ABORT（中途暂停给客户端断开窗口，恢复写时感知断连）、ABORT_MIDWAY（上游突断）；
 * - 记录 cancelled（取消信号是否送达）与 billed（供应商侧计费）——Q6「断开 ≠ 停止计费」的验证锚点。
 */
public class MockOpenAiUpstream implements AutoCloseable {

    public enum Fault { NORMAL, CLIENT_ABORT, ABORT_MIDWAY }

    private final com.sun.net.httpserver.HttpServer server;
    private final Map<String, Fault> faultByRequest = new ConcurrentHashMap<>();
    private final Map<String, Boolean> cancelled = new ConcurrentHashMap<>();
    private final Map<String, Long> billed = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();

    public MockOpenAiUpstream() {
        try {
            server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/chat/completions", exchange -> {
            String requestId = exchange.getRequestHeaders().getFirst("X-Quotapilot-Request-Id");
            String bodyText = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            long maxTokens = 1000;
            try {
                JsonNode body = json.readTree(bodyText);
                maxTokens = body.path("max_tokens").asLong(1000);
            } catch (IOException ignored) {
                // 无效体按默认
            }
            Fault fault = faultByRequest.getOrDefault(requestId, Fault.NORMAL);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            try {
                for (int i = 0; i < 3; i++) {
                    if (fault == Fault.ABORT_MIDWAY && i == 1) {
                        // 上游突断：不给 usage、不补齐流
                        exchange.close();
                        return;
                    }
                    out.write(chunk("{\"id\":\"sup-openai-" + requestId + "\",\"choices\":[{\"delta\":"
                            + "{\"content\":\"t" + i + "\"}}]}"));
                    out.flush();
                    if (fault == Fault.CLIENT_ABORT && i == 1) {
                        Thread.sleep(1500); // 客户端断开窗口
                        // 断开后上游「照常继续输出」：洪泛足够大的数据量，使网关侧 socket 缓冲必然被填满，
                        // 从而其写回失败可被确定性地观测到（回环网络小数据写不会失败）
                        String filler = "{\"id\":\"sup-openai-" + requestId + "\",\"choices\":[{\"delta\":"
                                + "{\"content\":\"" + "x".repeat(1024) + "\"}}]}";
                        for (int k = 0; k < 500; k++) {
                            out.write(chunk(filler));
                            out.flush();
                        }
                    }
                }
                bill(requestId, maxTokens);
                out.write(chunk("{\"id\":\"sup-openai-" + requestId + "\",\"choices\":[],"
                        + "\"usage\":{\"total_tokens\":" + maxTokens + "}}"));
                out.flush();
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException clientGone) {
                // Q6：客户端断开 ≠ 上游停止计费 —— 上游照常完成计算并计费
                cancelled.put(requestId, true);
                bill(requestId, maxTokens);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
    }

    public void injectFault(String requestId, Fault fault) {
        faultByRequest.put(requestId, fault);
    }

    public boolean isCancelled(String requestId) {
        return Boolean.TRUE.equals(cancelled.get(requestId));
    }

    public long billedTokens(String requestId) {
        return billed.getOrDefault(requestId, 0L);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void start() {
        server.start();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void bill(String requestId, long tokens) {
        billed.merge(requestId, tokens, Long::sum);
    }

    private static byte[] chunk(String json) {
        return ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
    }
}
