package io.quotapilot.infra.alert;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quotapilot.alert.domain.Alert;
import io.quotapilot.alert.domain.AlertEmitterPort;
import io.quotapilot.alert.domain.AlertStorePort;

/**
 * [M9] 告警出口：持久化（面板查询）+ 日志 + Webhook 扩展点（可配 URL，尽力投递）。
 */
@Component
public class AlertHub implements AlertEmitterPort {

    private static final Logger log = LoggerFactory.getLogger(AlertHub.class);

    private final AlertStorePort store;
    private final ObjectMapper json;
    private final String webhookUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public AlertHub(AlertStorePort store, ObjectMapper json,
                    @Value("${quotapilot.alert-webhook-url:}") String webhookUrl) {
        this.store = store;
        this.json = json;
        this.webhookUrl = webhookUrl;
    }

    @Override
    public void emit(Alert alert) {
        try {
            store.save(alert);
        } catch (RuntimeException e) {
            log.error("告警持久化失败: {}", alert, e);
        }
        log.warn("[ALERT][{}][{}] account={} request={} {}", alert.severity(), alert.type(), alert.accountId(),
                alert.requestId(), alert.message());
        sendWebhook(alert);
    }

    private void sendWebhook(Alert alert) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        try {
            String body = json.writeValueAsString(alert);
            HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("Webhook 投递失败: {}", e.toString());
        }
    }
}
