package io.quotapilot.reserve.domain;

import java.time.Instant;
import java.util.List;
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
import org.springframework.data.redis.core.StringRedisTemplate;

import io.quotapilot.common.DomainExceptions;
import io.quotapilot.ledger.domain.LedgerEntryType;
import io.quotapilot.ledger.domain.LedgerQueryPort;
import io.quotapilot.ledger.domain.ReservationRepositoryPort;
import io.quotapilot.ledger.domain.ReservationStatus;
import io.quotapilot.pricing.domain.PriceCatalog;
import io.quotapilot.pricing.domain.Sku;
import io.quotapilot.quota.domain.QuotaRule;
import io.quotapilot.quota.domain.QuotaRulePort;
import io.quotapilot.settlement.domain.SweeperService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [M3/Q7/Q1] 并发预留验收（真实 Redis Lua + H2）：
 * - 并发 100 预留、预算只够 60 → 恰好 60 成功，不超卖，失败不残留；
 * - Redis 账本丢失 → sweeper 依据 DB 权威重建（Q1）。
 */
@SpringBootTest
class M3ReservationConcurrencyIT {

    @Autowired ReservationEngine engine;
    @Autowired PriceCatalog priceCatalog;
    @Autowired QuotaRulePort rulePort;
    @Autowired StringRedisTemplate redis;
    @Autowired LedgerQueryPort ledgerQuery;
    @Autowired ReservationRepositoryPort reservationRepo;
    @Autowired SweeperService sweeper;
    @Autowired io.quotapilot.ledger.domain.AccountPort accountPort;

    private String setupScenario(String tag, long limitMinor) {
        String user = "conc-" + tag + "-" + UUID.randomUUID();
        String model = "m-" + tag + "-" + UUID.randomUUID();
        rulePort.saveRule(new QuotaRule("rule-" + user, io.quotapilot.ledger.domain.ScopeType.USER, user, null,
                limitMinor, "CNY", false), "test", "tr");
        priceCatalog.publish(new Sku(model, io.quotapilot.pricing.domain.UsageType.TOKEN), 1L, "CNY", Instant.now());
        return user + "|" + model;
    }

    private String accountIdOf(String user) {
        return accountPort.findByScope(io.quotapilot.ledger.domain.ScopeType.USER, user).orElseThrow().accountId();
    }

    @Test
    void 并发100预留_预算仅够60_恰好60成功且不超卖() throws Exception {
        String[] scenario = setupScenario("oversell", 6_000L).split("\\|");
        String user = scenario[0];
        String model = scenario[1];
        // 预建账户，隔离「并发建户」竞态，确保压测量化的是预留门本身
        accountPort.getOrCreate(io.quotapilot.ledger.domain.ScopeType.USER, user, 6_000L, "CNY");

        int threads = 100;
        long units = 100L; // 单请求预留 100 minor（价格 1/token × 100 units）
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ConcurrentLinkedQueue<String> rejectedIds = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            final String requestId = "req-" + user + "-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    engine.reserve(new ReserveCommand(requestId,
                            new ReserveCommand.ScopeValues(user, null, null), model, "TOKEN", units, 0, "tr"));
                    ok.incrementAndGet();
                } catch (DomainExceptions.QuotaExceeded e) {
                    rejected.incrementAndGet();
                    rejectedIds.add(requestId);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(ok.get()).isEqualTo(60);   // 预算 6000 / 100 = 60，恰好放行 60
        assertThat(rejected.get()).isEqualTo(40);
        // Redis 门 held = 6000（不超卖）
        var held = redis.opsForHash().get("account:balance:" + accountIdOf(user), "held");
        assertThat(Long.parseLong(String.valueOf(held))).isEqualTo(6_000L);
        // 每个成功请求在 DB 都有 RESERVED 记录；被拒绝请求不残留任何 hold
        long reservedCount = reservationRepo.findByAccount(accountIdOf(user), 200).stream()
                .filter(r -> r.getStatus() == ReservationStatus.RESERVED).count();
        assertThat(reservedCount).isEqualTo(60);
        assertThat(rejectedIds).allSatisfy(id -> assertThat(reservationRepo.findByRequestId(id)).isEmpty());
        // DB 账本 HOLD 流水合计 = 6000
        assertThat(ledgerQuery.sumsByType(accountIdOf(user)).get(LedgerEntryType.HOLD)).isEqualTo(6_000L);
    }

    @Test
    void Q1_Redis账本丢失后_sweeper依据DB权威重建() {
        String[] scenario = setupScenario("rebuild", 5_000L).split("\\|");
        String user = scenario[0];
        String model = scenario[1];

        ReserveResult r = engine.reserve(new ReserveCommand("req-rb-" + user,
                new ReserveCommand.ScopeValues(user, null, null), model, "TOKEN", 1000L, 0, "tr"));
        assertThat(r.duplicate()).isFalse();
        String accountId = r.accountId();

        // 模拟 Redis 账本丢失
        redis.delete("account:balance:" + accountId);
        Boolean exists = redis.hasKey("account:balance:" + accountId);
        assertThat(exists).isFalse();

        // sweeper 对账：以 DB（HOLD 1000 / settled 0）重建
        sweeper.reconcileRedis(accountPort, ledgerQuery);
        var held = redis.opsForHash().get("account:balance:" + accountId, "held");
        var limit = redis.opsForHash().get("account:balance:" + accountId, "limit");
        assertThat(Long.parseLong(String.valueOf(held))).isEqualTo(1_000L);
        assertThat(Long.parseLong(String.valueOf(limit))).isEqualTo(5_000L);

        // 重建后预留门继续正确工作：剩余可用 4000
        engine.reserve(new ReserveCommand("req-rb2-" + user,
                new ReserveCommand.ScopeValues(user, null, null), model, "TOKEN", 4000L, 0, "tr"));
        assertThatThrownByQuotaExceeded(user, model);
    }

    private void assertThatThrownByQuotaExceeded(String user, String model) {
        try {
            engine.reserve(new ReserveCommand("req-rb3-" + user,
                    new ReserveCommand.ScopeValues(user, null, null), model, "TOKEN", 1L, 0, "tr"));
            throw new AssertionError("应拒绝但通过了");
        } catch (DomainExceptions.QuotaExceeded expected) {
            // 预期
        }
    }
}
