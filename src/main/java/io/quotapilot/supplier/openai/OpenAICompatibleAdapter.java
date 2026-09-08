package io.quotapilot.supplier.openai;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quotapilot.supplier.domain.StreamedResponse;
import io.quotapilot.supplier.domain.SupplierCallException;
import io.quotapilot.supplier.domain.SupplierSpi;

/**
 * [M10/V1.1] OpenAI 兼容适配器：真实 HTTP 协议（chat/completions 流式，SSE 中解析 usage）。
 * - 请求强制 stream=true 且 stream_options.include_usage=true，把 model/max_tokens 纳入估计与结算；
 * - 透传 X-QuotaPilot-Request-Id 便于上游关联回调/账单；
 * - close() = 取消上游连接（Q6：取消 ≠ 停止计费，敞口兜底）；
 * - subscribeUsage 返回空：OpenAI 无按请求拉取接口，对账走账单导入/回调（文档约定）。
 */
public class OpenAICompatibleAdapter implements SupplierSpi {

    private final String baseUrl;
    private final String apiKey;
    private final long defaultMaxTokens;
    private final Duration connectTimeout;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;

    public OpenAICompatibleAdapter(String baseUrl, String apiKey, long defaultMaxTokens, long connectTimeoutMs) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.defaultMaxTokens = defaultMaxTokens;
        this.connectTimeout = Duration.ofMillis(connectTimeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        org.slf4j.LoggerFactory.getLogger(OpenAICompatibleAdapter.class)
                .info("[M10] OpenAI 兼容适配器已启用 baseUrl={}", this.baseUrl);
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public EstimatedUsage estimateUsage(String model) {
        return new EstimatedUsage(defaultMaxTokens, false);
    }

    @Override
    public StreamedResponse call(SupplierCallRequest request, HoldContext holdContext) throws SupplierCallException {
        ObjectNode body = json.createObjectNode();
        body.put("model", request.model());
        body.put("stream", true);
        ObjectNode streamOpts = body.putObject("stream_options");
        streamOpts.put("include_usage", true);
        body.put("max_tokens", Math.max(1, request.maxUnits()));
        ArrayNode messages = body.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", request.payloadJson() == null ? "ping" : request.payloadJson());

        HttpRequest httpReq;
        java.net.URI target = URI.create(baseUrl + "/chat/completions");
        try {
            httpReq = HttpRequest.newBuilder(target)
                    .timeout(connectTimeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-QuotaPilot-Request-Id", request.requestId())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
        } catch (Exception e) {
            throw new SupplierCallException("openai: build request failed", false, 0, e);
        }
        HttpResponse<InputStream> response;
        try {
            response = http.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.http.HttpTimeoutException e) {
            // 超时：请求可能已到达供应商（结果未知，Q2/Q6）
            throw new SupplierCallException("openai: timeout after dispatch", true, 0, e);
        } catch (IOException e) {
            // 连接建立/发送失败：确定未触达
            throw new SupplierCallException("openai: dispatch failed: " + e.getMessage(), false, 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SupplierCallException("openai: interrupted", true, 0, e);
        }
        if (response.statusCode() >= 400) {
            // 已发出但被拒绝（4xx/5xx）：保守按「可能已计费」处理（P2 诚实计量）
            throw new SupplierCallException("openai: http " + response.statusCode(), true, 0, null);
        }
        return new StreamedResponse(response.body(), response.headers().map(), () -> {
            try {
                response.body().close(); // 关闭底层连接 = 尽力取消上游（Q6）
            } catch (IOException ignored) {
                // 取消失败不阻断释放流程
            }
        });
    }

    @Override
    public ActualUsage parseUsage(String responseBody) {
        // 兼容两种形态：完整 JSON（含 usage 字段）与已累积的 SSE 文本（取最后一个 usage 块）
        String trimmed = responseBody.trim();
        if (trimmed.startsWith("{")) {
            try {
                JsonNode root = json.readTree(trimmed);
                return new ActualUsage(root.path("id").asText(null),
                        root.path("usage").path("total_tokens").asLong(0), true);
            } catch (IOException e) {
                return new ActualUsage(null, 0, false);
            }
        }
        long tokens = 0;
        String id = null;
        for (String line : trimmed.split("\n")) {
            line = line.trim();
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            try {
                JsonNode node = json.readTree(payload);
                if (node.hasNonNull("id")) {
                    id = node.get("id").asText();
                }
                JsonNode usage = node.get("usage");
                if (usage != null && usage.has("total_tokens")) {
                    tokens = usage.get("total_tokens").asLong(0);
                }
            } catch (IOException ignored) {
                // 跳过非 JSON 行（注释/空行）
            }
        }
        return new ActualUsage(id, tokens, true);
    }

    @Override
    public List<UsageRecord> subscribeUsage(String requestId) {
        // OpenAI 协议无按请求拉取用量的接口：对账经由账单快照导入或供应商回调（见 M5）
        return List.of();
    }
}
