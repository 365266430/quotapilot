package io.quotapilot.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import io.quotapilot.pricing.domain.PriceCatalog;
import io.quotapilot.pricing.domain.Sku;
import io.quotapilot.supplier.mock.MockSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [API/§8] 对外契约验收（HTTP 全链路）：额度配置 → 预留/取消 → 回调幂等 → 面板 → 管理。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiContractIT {

    @Autowired TestRestTemplate rest;
    @Autowired MockSupplier mock;
    @Autowired PriceCatalog priceCatalog;

    static String model;
    static String user;

    @BeforeAll
    static void setup(@Autowired PriceCatalog catalog, @Autowired io.quotapilot.quota.domain.QuotaRulePort rules) {
        model = "api-" + UUID.randomUUID();
        user = "api-user-" + UUID.randomUUID();
        catalog.publish(new Sku(model, io.quotapilot.pricing.domain.UsageType.TOKEN), 1L, "CNY", Instant.now());
        rules.saveRule(new io.quotapilot.quota.domain.QuotaRule("rule-api-" + user,
                io.quotapilot.ledger.domain.ScopeType.USER, user, null, 50_000L, "CNY", false), "test", "tr");
    }

    private HttpHeaders json() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void 完整API契约链路() {
        // 1. 发布价格版本（M2）
        var priceResp = rest.postForEntity("/v1/prices/versions", new HttpEntity<>(
                Map.of("model", "api-extra-" + UUID.randomUUID(), "usageType", "TOKEN",
                        "pricePerUnitMinor", 2), json()), Map.class);
        assertThat(priceResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 2. 预留不执行（reserveOnly）
        String rid = "api-res-" + UUID.randomUUID();
        var reserve = rest.postForEntity("/v1/requests", new HttpEntity<>(Map.of(
                "requestId", rid, "userId", user, "model", model, "declaredEstimatedUnits", 1000,
                "reserveOnly", true), json()), Map.class);
        assertThat(reserve.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reserve.getBody().get("status")).isEqualTo("RESERVED");
        assertThat(reserve.getBody().get("holdId")).isNotNull();

        // 3. 状态查询
        var status = rest.getForEntity("/v1/requests/" + rid, Map.class);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody().get("reservationStatus")).isEqualTo("RESERVED");

        // 4. 取消 → 释放
        var cancel = rest.exchange("/v1/requests/" + rid, org.springframework.http.HttpMethod.DELETE,
                new HttpEntity<>(json()), Map.class);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancel.getBody().get("status")).isEqualTo("RELEASED");

        // 5. 预算不足 → 402 QUOTA_EXCEEDED
        String rid2 = "api-poor-" + UUID.randomUUID();
        var poor = rest.postForEntity("/v1/requests", new HttpEntity<>(Map.of(
                "requestId", rid2, "userId", user, "model", model, "declaredEstimatedUnits", 999_999_999L), json()),
                Map.class);
        assertThat(poor.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(poor.getBody().get("code")).isEqualTo("QUOTA_EXCEEDED");

        // 6. 发出前失败 → 释放且无敞口（确定未触达供应商）
        String rid3 = "api-predis-" + UUID.randomUUID();
        mock.injectFault(rid3, MockSupplier.Fault.FAIL_BEFORE_DISPATCH);
        var predis = rest.postForEntity("/v1/requests", new HttpEntity<>(Map.of(
                "requestId", rid3, "userId", user, "model", model, "declaredEstimatedUnits", 500), json()), Map.class);
        assertThat(predis.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(predis.getBody().get("status")).isEqualTo("RELEASED");
        assertThat(predis.getBody().get("releaseReason")).isEqualTo("PRE_DISPATCH_ERROR");
        assertThat(predis.getBody().get("exposureId")).isNull();

        // 7. 成功请求 → 回调重复投递 → 409 DUPLICATE_EVENT
        String rid4 = "api-ok-" + UUID.randomUUID();
        var ok = rest.postForEntity("/v1/requests", new HttpEntity<>(Map.of(
                "requestId", rid4, "userId", user, "model", model, "declaredEstimatedUnits", 600), json()), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("status")).isEqualTo("SUCCEEDED");

        var cb1 = rest.postForEntity("/v1/callbacks/mock", new HttpEntity<>(Map.of(
                "requestId", rid4, "supplierRequestId", "sup-" + rid4, "units", 600, "seq", 1), json()), Map.class);
        var cb2 = rest.postForEntity("/v1/callbacks/mock", new HttpEntity<>(Map.of(
                "requestId", rid4, "supplierRequestId", "sup-" + rid4, "units", 600, "seq", 1), json()), Map.class);
        assertThat(cb1.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(cb2.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(cb2.getBody().get("status")).isEqualTo("DUPLICATE_EVENT");

        // 8. 余额（M0 权威口径）
        String accountId = String.valueOf(ok.getBody().get("accountId"));
        var balance = rest.getForEntity("/v1/accounts/" + accountId + "/balance", Map.class);
        assertThat(balance.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(balance.getBody().get("limitMinor")).isEqualTo(50_000);

        // 9. 面板（M9）
        var dash = rest.getForEntity("/v1/dashboards?scope=" + accountId, Map.class);
        assertThat(dash.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dash.getBody()).containsKeys("balance", "recentAlerts");

        // 10. 使用明细（M8）
        var usages = rest.getForEntity("/v1/usages?accountId=" + accountId + "&page=0&size=10", Map.class);
        assertThat(usages.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) usages.getBody().get("total")).isGreaterThanOrEqualTo(1);

        // 11. 供应商侧账本（M10）
        var sup = rest.getForEntity("/v1/admin/supplier-ledger?accountId=" + accountId, java.util.List.class);
        assertThat(sup.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 12. 手工 sweep（M11）
        var sweep = rest.postForEntity("/v1/admin/sweep", new HttpEntity<>(json()), Map.class);
        assertThat(sweep.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 13. 硬止损 BLOCKED → 402 ACCOUNT_BLOCKED（M1）
        var block = rest.postForEntity("/v1/admin/accounts/" + accountId + "/status",
                new HttpEntity<>(Map.of("status", "BLOCKED"), json()), Map.class);
        assertThat(block.getStatusCode()).isEqualTo(HttpStatus.OK);
        String rid5 = "api-blocked-" + UUID.randomUUID();
        var blocked = rest.postForEntity("/v1/requests", new HttpEntity<>(Map.of(
                "requestId", rid5, "userId", user, "model", model, "declaredEstimatedUnits", 100), json()), Map.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(blocked.getBody().get("code")).isEqualTo("ACCOUNT_BLOCKED");

        // 14. 不存在的账户 → 404
        var missing = rest.getForEntity("/v1/accounts/nope-" + UUID.randomUUID() + "/balance", Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().get("code")).isEqualTo("NOT_FOUND");

        // 15. M9 Web 面板（静态单页）
        var panel = rest.getForEntity("/", String.class);
        assertThat(panel.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(panel.getBody()).contains("QuotaPilot").contains("available");
    }
}
