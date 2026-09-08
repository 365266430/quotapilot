package io.quotapilot.quota.domain;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.quotapilot.common.DomainExceptions;
import io.quotapilot.dashboard.domain.DashboardService;
import io.quotapilot.gateway.domain.GatewayOrchestrator;
import io.quotapilot.gateway.domain.GatewayRequest;
import io.quotapilot.gateway.domain.GatewayResult;
import io.quotapilot.ledger.domain.ScopeType;
import io.quotapilot.pricing.domain.PriceCatalog;
import io.quotapilot.pricing.domain.Sku;
import io.quotapilot.alert.domain.AlertType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [V1.1 运维项] 团队分摊限额迁移（成员变化 → 存量成员账户限额同步）+ 调度器冒烟（自动对账/告警评估）。
 */
@SpringBootTest
class V11OpsIT {

    @Autowired QuotaService quotaService;
    @Autowired QuotaRulePort rulePort;
    @Autowired PriceCatalog priceCatalog;
    @Autowired io.quotapilot.ledger.domain.AccountPort accountPort;
    @Autowired GatewayOrchestrator orchestrator;
    @Autowired DashboardService dashboard;
    // 测试配置禁用了调度器（scheduler.enabled=false）：手动构造以验证调度逻辑本身
    @Autowired io.quotapilot.reconcile.domain.ReconciliationService reconciliationService;
    @Autowired io.quotapilot.infra.redis.RedisLock redisLock;

    @Test
    void 团队分摊_成员加入_存量成员账户限额迁移() {
        String team = "team-" + UUID.randomUUID();
        String model = "m-" + UUID.randomUUID();
        String u1 = "u1-" + UUID.randomUUID();
        String u2 = "u2-" + UUID.randomUUID();
        String u3 = "u3-" + UUID.randomUUID();
        rulePort.saveRule(new QuotaRule("rule-" + team, ScopeType.TEAM, team, null, 100L, "CNY", true),
                "test", "tr");
        priceCatalog.publish(new Sku(model, io.quotapilot.pricing.domain.UsageType.TOKEN), 1L, "CNY", Instant.now());
        quotaService.addTeamMember(team, u1);
        quotaService.addTeamMember(team, u2);

        // u1 首次请求：按 2 人分摊限额 50 建户，预留 30 成功
        GatewayResult r1 = orchestrator.execute(new GatewayRequest("v11-" + UUID.randomUUID(), u1, team, null,
                model, 30L, null, false, 0, null, null));
        assertThat(r1.status()).isEqualTo(GatewayResult.SUCCEEDED);
        var acct = accountPort.findByScope(ScopeType.USER, u1).orElseThrow();
        assertThat(acct.quotaLimitMinor()).isEqualTo(50L);

        // 新成员加入 → 分摊限额 34 → 存量成员账户限额迁移（在途按快照不受影响）
        quotaService.addTeamMember(team, u3);
        assertThat(accountPort.findByScope(ScopeType.USER, u1).orElseThrow().quotaLimitMinor()).isEqualTo(34L);
        // u2 从未请求过：无账户（按设计），首次请求将按新分摊限额 34 建户
        assertThat(accountPort.findByScope(ScopeType.USER, u2)).isEmpty();
        assertThatThrownBy(() -> orchestrator.execute(new GatewayRequest("v11-" + UUID.randomUUID(), u2, team, null,
                model, 40L, null, false, 0, null, null)))
                .isInstanceOf(DomainExceptions.QuotaExceeded.class);
        assertThat(accountPort.findByScope(ScopeType.USER, u2).orElseThrow().quotaLimitMinor()).isEqualTo(34L);

        // 已用 30 + 新预留 10 = 40 > 34 → 拒绝（Redis 门已同步）
        assertThatThrownBy(() -> orchestrator.execute(new GatewayRequest("v11-" + UUID.randomUUID(), u1, team, null,
                model, 10L, null, false, 0, null, null)))
                .isInstanceOf(DomainExceptions.QuotaExceeded.class);
    }

    @Test
    void 调度器冒烟_自动对账与告警评估_无异常执行() {
        // 自动对账（空账本场景）：幂等、无异常
        new io.quotapilot.infra.sweep.ReconcileScheduler(reconciliationService, redisLock).reconcile();
        // 告警评估（全账户扫描）：无异常
        new io.quotapilot.infra.alert.AlertEvaluationScheduler(accountPort, dashboard, redisLock).evaluate();
    }

    @Test
    void M9_告警评估_可用额度低于阈值触发并可查询() {
        String user = "m9-" + UUID.randomUUID();
        String model = "m9-" + UUID.randomUUID();
        rulePort.saveRule(new QuotaRule("rule-" + user, ScopeType.USER, user, null, 100L, "CNY", false), "test", "tr");
        priceCatalog.publish(new Sku(model, io.quotapilot.pricing.domain.UsageType.TOKEN), 1L, "CNY", Instant.now());
        assertThat(priceCatalog.resolve(new Sku(model, io.quotapilot.pricing.domain.UsageType.TOKEN), Instant.now()))
                .as("价格快照应立即可解析").isPresent();
        // 消费 95% → 可用 5% < 20% 阈值
        orchestrator.execute(new GatewayRequest("m9-" + UUID.randomUUID(), user, null, null, model, 95L, null,
                false, 0, null, null));
        String accountId = accountPort.findByScope(ScopeType.USER, user).orElseThrow().accountId();
        dashboard.balance(accountId); // 触发评估
        new io.quotapilot.infra.alert.AlertEvaluationScheduler(accountPort, dashboard, redisLock).evaluate();
        assertThat(dashboard.recentAlerts(50))
                .anyMatch(a -> a.type() == AlertType.AVAILABLE_LOW && accountId.equals(a.accountId()));
    }
}
