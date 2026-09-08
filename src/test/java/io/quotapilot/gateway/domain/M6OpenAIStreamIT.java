package io.quotapilot.gateway.domain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.quotapilot.ledger.domain.ExposureRepositoryPort;
import io.quotapilot.ledger.domain.ExposureState;
import io.quotapilot.ledger.domain.LedgerQueryPort;
import io.quotapilot.ledger.domain.ReservationRepositoryPort;
import io.quotapilot.ledger.domain.ReservationStatus;
import io.quotapilot.metering.domain.UsageSource;
import io.quotapilot.metering.domain.UsageEventPort;
import io.quotapilot.pricing.domain.PriceCatalog;
import io.quotapilot.pricing.domain.Sku;
import io.quotapilot.quota.domain.QuotaRule;
import io.quotapilot.quota.domain.QuotaRulePort;
import io.quotapilot.settlement.domain.SweeperService;
import io.quotapilot.support.MockOpenAiUpstream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [M6/M10/V1.1] 流式代理与 OpenAI 兼容协议端到端验收（内嵌真实 HTTP SSE 上游）：
 * - OpenAI 适配器：真实 HTTP 流式调用 + SSE usage 解析 + 正确结算；
 * - 流式代理：SSE 逐行透传 + quotapilot 元事件；
 * - Q6 客户端断连：取消信号送达上游 + 上游照常计费 + 敞口等待回调 → 迟到回调正常结算；
 * - 上游突断：结果未知 → 敞口 → 宽限期过按 estimate 封顶关闭。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class M6OpenAIStreamIT {

    static final MockOpenAiUpstream UPSTREAM = new MockOpenAiUpstream();

    @DynamicPropertySource
    static void upstreamProps(DynamicPropertyRegistry registry) {
        UPSTREAM.start();
        registry.add("quotapilot.suppliers.openai.enabled", () -> "true");
        registry.add("quotapilot.suppliers.openai.base-url", () -> "http://localhost:" + UPSTREAM.port() + "/v1");
        registry.add("quotapilot.suppliers.openai.api-key", () -> "test-key");
        registry.add("quotapilot.suppliers.openai.default-max-tokens", () -> "1000");
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.close();
    }

    @Autowired TestRestTemplate rest;
    @Autowired QuotaRulePort quotaRulePort;
    @Autowired PriceCatalog priceCatalog;
    @Autowired io.quotapilot.ledger.domain.AccountPort accountPort;
    @Autowired ReservationRepositoryPort reservationRepo;
    @Autowired ExposureRepositoryPort exposureRepo;
    @Autowired LedgerQueryPort ledgerQuery;
    @Autowired UsageEventPort usageEvents;
    @Autowired SweeperService sweeper;
    @Autowired io.quotapilot.gateway.domain.GatewayOrchestrator orchestrator;

    String user;
    String model;
    String accountId;

    void setupScenario(String tag) {
        user = "m6-" + tag + "-" + UUID.randomUUID();
        model = "gpt-m6-" + tag + "-" + UUID.randomUUID();
        quotaRulePort.saveRule(new QuotaRule("rule-" + user, io.quotapilot.ledger.domain.ScopeType.USER, user, null,
                100_000L, "CNY", false), "test", "tr");
        priceCatalog.publish(new Sku(model, io.quotapilot.pricing.domain.UsageType.TOKEN), 2L, "CNY", Instant.now());
        accountId = accountPort.getOrCreate(io.quotapilot.ledger.domain.ScopeType.USER, user, 100_000L, "CNY")
                .accountId();
    }

    @Test
    void openai适配器_非流式路径_真实HTTP_SSE解析_按usage结算() {
        setupScenario("nonstream");
        String rid = "m6-ns-" + UUID.randomUUID();
        var result = orchestrator.execute(new GatewayRequest(rid, user, null, null, model, 400L, "hello", false, 0,
                null, "openai"));
        assertThat(result.status()).isEqualTo(GatewayResult.SUCCEEDED);
        assertThat(result.usageUnits()).isEqualTo(400L);          // SSE usage 块解析
        assertThat(result.chargedMinor()).isEqualTo(800L);        // 400 × 2（快照价）
        assertThat(usageEvents.findByRequestId(rid).stream()
                .anyMatch(e -> e.source() == UsageSource.OPENAI && e.quantity() == 400L)).isTrue();
        assertThat(reservationRepo.findByRequestId(rid).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.SETTLED);
    }

    @Test
    void 流式代理_SSE透传_元事件结算() throws Exception {
        setupScenario("stream");
        String rid = "m6-st-" + UUID.randomUUID();
        HttpResponse<InputStream> resp = openStream(rid, 500L, null);
        BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
        StringBuilder received = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            received.append(line).append("\n");
        }
        String body = received.toString();
        // SSE 块逐行透传
        assertThat(body).contains("\"delta\"").contains("[DONE]");
        // quotapilot 元事件：按 usage 正确结算（500 × 2）
        assertThat(body).contains("\"status\":\"SETTLED\"").contains("\"chargedMinor\":1000");
        assertThat(UPSTREAM.billedTokens(rid)).isEqualTo(500L);
        assertThat(reservationRepo.findByRequestId(rid).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.SETTLED);
    }

    @Test
    void Q6_客户端中途断连_取消信号送达上游_上游照常计费_迟到回调正常结算() throws Exception {
        setupScenario("abort");
        String rid = "m6-abort-" + UUID.randomUUID();
        UPSTREAM.injectFault(rid, MockOpenAiUpstream.Fault.CLIENT_ABORT);
        openAndAbortStream(rid, 600L); // 读到第一个 SSE 事件后强制 RST 断连

        // 1) 取消信号送达上游（close = 尽力取消上游）
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> UPSTREAM.isCancelled(rid));
        // 2) 上游照常完成计算并计费（断开 ≠ 停止计费，P2/Q6）
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> UPSTREAM.billedTokens(rid) == 600L);
        // 3) 网关侧：请求已释放 + 敞口 PENDING（等待对账）
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(reservationRepo.findByRequestId(rid).orElseThrow().getStatus())
                    .isEqualTo(ReservationStatus.RELEASED);
            assertThat(exposureRepo.findByRequestId(rid).orElseThrow().getState()).isEqualTo(ExposureState.PENDING);
        });

        // 4) 供应商迟到回调到达（经公开回调 API，模拟真实供应商行为）→ 敞口 SETTLED 正常结算，不双重扣费
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        var callback = rest.postForEntity("/v1/callbacks/openai", new HttpEntity<>(Map.of(
                "requestId", rid, "supplierRequestId", "sup-openai-" + rid, "units", 600L, "seq", 1), json),
                Map.class);
        assertThat(callback.getStatusCode().is2xxSuccessful()).isTrue();
        var exposure = exposureRepo.findByRequestId(rid).orElseThrow();
        assertThat(exposure.getState()).isEqualTo(ExposureState.SETTLED);
        assertThat(exposure.getResolvedAmountMinor()).isEqualTo(1200L); // 600 × 2
        assertThat(ledgerQuery.netChargedByRequest(rid)).isEqualTo(1200L);
    }

    @Test
    void 上游突断_结果未知转敞口_宽限期过按estimate封顶关闭() throws Exception {
        setupScenario("upstreambreak");
        String rid = "m6-brk-" + UUID.randomUUID();
        UPSTREAM.injectFault(rid, MockOpenAiUpstream.Fault.ABORT_MIDWAY);
        HttpResponse<InputStream> resp = openStream(rid, 700L, null);
        BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
        while (reader.readLine() != null) {
            // 读到上游断流为止
        }
        // 网关侧：释放 + 敞口（无 usage → 宽限期后按 estimate 封顶）
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(reservationRepo.findByRequestId(rid).orElseThrow().getStatus())
                    .isEqualTo(ReservationStatus.RELEASED);
            assertThat(exposureRepo.findByRequestId(rid).orElseThrow().getState()).isEqualTo(ExposureState.PENDING);
        });
        // 宽限期 2s → sweeper 封顶关闭
        Awaitility.await().pollInterval(300, TimeUnit.MILLISECONDS).atMost(10, TimeUnit.SECONDS)
                .until(() -> exposureRepo.findByRequestId(rid).orElseThrow().isPastGrace(Instant.now()));
        sweeper.sweepExpiredExposures();
        var exposure = exposureRepo.findByRequestId(rid).orElseThrow();
        assertThat(exposure.getState()).isEqualTo(ExposureState.CLOSED_WITH_ADJUSTMENT);
        assertThat(exposure.getResolvedAmountMinor()).isEqualTo(1400L); // estimate 700 × 2 封顶
    }

    /** 用原始 Socket 发起流式请求，读到第一个 SSE 事件后以 RST 强制断连（真实模拟客户端中途断开）。 */
    private void openAndAbortStream(String requestId, long maxTokens) throws Exception {
        URI uri = URI.create(rest.getRootUri() + "/v1/requests/stream");
        String body = "{\"requestId\":\"" + requestId + "\",\"userId\":\"" + user + "\",\"model\":\"" + model
                + "\",\"declaredEstimatedUnits\":" + maxTokens + ",\"supplier\":\"openai\"}";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        java.net.Socket socket = new java.net.Socket(java.net.InetAddress.getByName(uri.getHost()), uri.getPort());
        socket.setSoTimeout(20000);
        String head = "POST /v1/requests/stream HTTP/1.0\r\nHost: " + uri.getHost() + "\r\n"
                + "Content-Type: application/json\r\nContent-Length: " + bodyBytes.length + "\r\n\r\n";
        socket.getOutputStream().write(head.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().write(bodyBytes);
        socket.getOutputStream().flush();
        InputStream in = socket.getInputStream();
        StringBuilder received = new StringBuilder();
        int headerEnd = -1;
        while (true) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            received.append((char) b);
            String s = received.toString();
            if (headerEnd < 0) {
                headerEnd = s.indexOf("\r\n\r\n");
                continue;
            }
            // 已越过响应头：读到第一个完整 SSE 事件（"data:" 后的 "\n\n"）即停止
            String eventPart = s.substring(headerEnd + 4);
            if (eventPart.startsWith("data:") && eventPart.endsWith("\n\n")) {
                break;
            }
        }
        assertThat(received.toString()).contains("data:");
        // 强制 RST 断连（setSoLinger(0) + close），而非优雅半关闭
        socket.setSoLinger(true, 0);
        socket.close();
    }

    private HttpResponse<InputStream> openStream(String requestId, long maxTokens, String supplierOverride)
            throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().build();
        String body = "{\"requestId\":\"" + requestId + "\",\"userId\":\"" + user + "\",\"model\":\"" + model
                + "\",\"declaredEstimatedUnits\":" + maxTokens + ",\"supplier\":\""
                + (supplierOverride == null ? "openai" : supplierOverride) + "\"}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(rest.getRootUri() + "/v1/requests/stream"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("stream open failed: " + resp.statusCode() + " body="
                    + new String(resp.body().readAllBytes(), StandardCharsets.UTF_8));
        }
        return resp;
    }
}
