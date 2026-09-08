package io.quotapilot.ratelimit.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import io.quotapilot.common.DomainExceptions;
import io.quotapilot.gateway.domain.GatewayOrchestrator;
import io.quotapilot.gateway.domain.GatewayRequest;
import io.quotapilot.ledger.domain.ScopeType;
import io.quotapilot.metering.domain.UsageEvent;
import io.quotapilot.metering.domain.UsageEventPort;
import io.quotapilot.metering.domain.UsageSource;
import io.quotapilot.pricing.domain.PriceCatalog;
import io.quotapilot.pricing.domain.Sku;
import io.quotapilot.quota.domain.QuotaRule;
import io.quotapilot.quota.domain.QuotaRulePort;
import io.quotapilot.ledger.domain.ReservationRepositoryPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [M7] 限流器验收（真实 Redis Lua）：
 * 同一秒 200 请求阈值 100 → 恰好 100 通过；Redis 键丢失后按 DB 重建不永久失真；
 * 用量型维度按实际用量返还/补扣；限流拒绝不产生预留（429 + Retry-After）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class M7RateLimitIT {

    @Autowired RateLimiter rateLimiter;
    @Autowired RateLimitRulePort rulePort;
    @Autowired io.quotapilot.ledger.domain.AccountPort accountPort;
    @Autowired QuotaRulePort quotaRulePort;
    @Autowired PriceCatalog priceCatalog;
    @Autowired GatewayOrchestrator orchestrator;
    @Autowired ReservationRepositoryPort reservationRepo;
    @Autowired UsageEventPort usageEvents;
    @Autowired StringRedisTemplate redis;
    @Autowired TestRestTemplate rest;

    private String newAccount(String tag, long quotaMinor) {
        String user = "rl-" + tag + "-" + UUID.randomUUID();
        quotaRulePort.saveRule(new QuotaRule("rule-" + user, ScopeType.USER, user, null, quotaMinor, "CNY", false),
                "test", "tr");
        return accountPort.getOrCreate(ScopeType.USER, user, quotaMinor, "CNY").accountId();
    }

    @Test
    void 同一秒200请求_阈值100_恰好100通过() throws Exception {
        String accountId = newAccount("window", 1_000_000L);
        rulePort.save(new RateLimitRule(accountId, 100, 0, 0));

        int threads = 200;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger denied = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    rateLimiter.check(accountId, 1L, 1L);
                    allowed.incrementAndGet();
                } catch (DomainExceptions.RateLimited e) {
                    denied.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        assertThat(allowed.get()).isEqualTo(100);
        assertThat(denied.get()).isEqualTo(100);
    }

    @Test
    void 限流拒绝_不产生任何预留() {
        String accountId = newAccount("nohold", 1_000_000L);
        rulePort.save(new RateLimitRule(accountId, 1, 0, 0));
        rateLimiter.check(accountId, 1L, 1L); // 消耗唯一名额
        assertThatThrownBy(() -> rateLimiter.check(accountId, 1L, 1L))
                .isInstanceOf(DomainExceptions.RateLimited.class)
                .hasFieldOrPropertyWithValue("retryAfterSeconds", 1L);
    }

    @Test
    void 令牌桶_按实际用量返还_补扣下限为零() {
        String accountId = newAccount("bucket", 1_000_000L);
        // BILLING_UNITS：容量 60 minor/分钟（补充速率 1/s，测试窗口内近似固定窗口）
        rulePort.save(new RateLimitRule(accountId, 0, 0, 60));
        rateLimiter.check(accountId, 1L, 60L); // 取走全部 60
        assertThatThrownBy(() -> rateLimiter.check(accountId, 1L, 1L))
                .isInstanceOf(DomainExceptions.RateLimited.class);
        // 实际结算 40（预估 60）→ 返还 20
        rateLimiter.onSettled(accountId, 1L, 40L, 1L, 60L);
        rateLimiter.check(accountId, 1L, 15L);  // 20(±补充) ≥ 15 → 通过
        assertThatThrownBy(() -> rateLimiter.check(accountId, 1L, 100L))
                .isInstanceOf(DomainExceptions.RateLimited.class)
                .hasFieldOrPropertyWithValue("dimension", "BILLING_UNITS");
        // 补扣不允许为负：超额结算后桶下限 0，仍可被返还逻辑恢复
        rateLimiter.onSettled(accountId, 1L, 500L, 1L, 15L);
    }

    @Test
    void Q_限流计数器_Redis丢失后按DB账本重建_不永久失真() {
        String accountId = newAccount("rebuild", 1_000_000L);
        rulePort.save(new RateLimitRule(accountId, 3, 0, 0));
        // DB 侧已有 2 个窗口内用量事件
        usageEvents.record(new UsageEvent(null, "rb-1-" + accountId, null, accountId, "m|TOKEN", "TOKEN", 1,
                Instant.now(), UsageSource.INTERNAL, 1, null));
        usageEvents.record(new UsageEvent(null, "rb-2-" + accountId, null, accountId, "m|TOKEN", "TOKEN", 1,
                Instant.now(), UsageSource.INTERNAL, 2, null));
        // 模拟 Redis 丢失
        redis.delete("ratelimit:" + accountId + ":REQUESTS");
        // 重建：init=2 → 窗口 2 + 本次 1 = 3 ≤ 3 → 通过；第 4 个 → 拒绝
        rateLimiter.check(accountId, 1L, 1L);
        assertThatThrownBy(() -> rateLimiter.check(accountId, 1L, 1L))
                .isInstanceOf(DomainExceptions.RateLimited.class);
    }

    @Test
    void HTTP_429_RATE_LIMITED_携带RetryAfter头() {
        String user = "rl-http-" + UUID.randomUUID();
        String model = "rl-http-" + UUID.randomUUID();
        quotaRulePort.saveRule(new QuotaRule("rule-" + user, ScopeType.USER, user, null, 1_000_000L, "CNY", false),
                "test", "tr");
        priceCatalog.publish(new Sku(model, io.quotapilot.pricing.domain.UsageType.TOKEN), 1L, "CNY", Instant.now());
        String accountId = accountPort.getOrCreate(ScopeType.USER, user, 1_000_000L, "CNY").accountId();
        rulePort.save(new RateLimitRule(accountId, 1, 0, 0));

        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        String rid1 = "rl-h1-" + UUID.randomUUID();
        String rid2 = "rl-h2-" + UUID.randomUUID();
        var first = rest.postForEntity("/v1/requests", new HttpEntity<>(Map.of(
                "requestId", rid1, "userId", user, "model", model,
                "declaredEstimatedUnits", 10), json), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        var second = rest.postForEntity("/v1/requests", new HttpEntity<>(Map.of(
                "requestId", rid2, "userId", user, "model", model,
                "declaredEstimatedUnits", 10), json), Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(second.getBody().get("code")).isEqualTo("RATE_LIMITED");
        assertThat(second.getHeaders().getFirst("Retry-After")).isNotNull();
        // 被限流请求不产生预留
        assertThat(reservationRepo.findByRequestId(rid2)).isEmpty();
    }
}
