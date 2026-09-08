package io.quotapilot.quota.domain;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quotapilot.ledger.domain.ScopeType;
import io.quotapilot.pricing.domain.PriceCatalog;
import io.quotapilot.pricing.domain.PriceSnapshot;
import io.quotapilot.pricing.domain.PriceVersion;
import io.quotapilot.pricing.domain.Sku;
import io.quotapilot.pricing.domain.UsageType;
import io.quotapilot.support.Support.FakePrices;
import io.quotapilot.support.Support.FakeRules;

import static org.assertj.core.api.Assertions.assertThat;

/** [M1/M2] 额度解析优先级 + 价格版本快照（规范验收：v3 生效期发起、v4 生效期结算仍按 v3）。 */
class M1M2DomainTest {

    private FakeRules rules;
    private DefaultQuotaPolicyResolver resolver;
    private FakePrices prices;
    private PriceCatalog catalog;

    @BeforeEach
    void setUp() {
        rules = new FakeRules();
        resolver = new DefaultQuotaPolicyResolver(rules, 1_000_000L, "CNY");
        prices = new FakePrices();
        catalog = new PriceCatalog(prices);
    }

    @Test
    void 解析优先级_task_高于_userModel_高于_teamModel_高于_user_高于_team_高于_model() {
        rules.saveRule(new QuotaRule("r-task", ScopeType.TASK, "t1", null, 100, "CNY", false), "op", "tr");
        rules.saveRule(new QuotaRule("r-um", ScopeType.USER_MODEL, "u1", "gpt", 200, "CNY", false), "op", "tr");
        rules.saveRule(new QuotaRule("r-tm", ScopeType.TEAM_MODEL, "team1", "gpt", 300, "CNY", false), "op", "tr");
        rules.saveRule(new QuotaRule("r-user", ScopeType.USER, "u1", null, 400, "CNY", false), "op", "tr");
        rules.saveRule(new QuotaRule("r-team", ScopeType.TEAM, "team1", null, 500, "CNY", false), "op", "tr");
        rules.saveRule(new QuotaRule("r-model", ScopeType.MODEL, "gpt", null, 600, "CNY", false), "op", "tr");

        ScopeContext full = ScopeContext.of("u1", "team1", "gpt", "t1");
        assertThat(resolver.resolve(full)).map(EffectiveQuota::ruleId).contains("r-task");
        assertThat(resolver.resolve(ScopeContext.of("u1", "team1", "gpt", null))).map(EffectiveQuota::ruleId)
                .contains("r-um");
        assertThat(resolver.resolve(ScopeContext.of(null, "team1", "gpt", null))).map(EffectiveQuota::ruleId)
                .contains("r-tm");
        assertThat(resolver.resolve(ScopeContext.of("u1", null, "other-model", null))).map(EffectiveQuota::ruleId)
                .contains("r-user");
        assertThat(resolver.resolve(ScopeContext.of(null, "team1", null, null))).map(EffectiveQuota::ruleId)
                .contains("r-team");
        assertThat(resolver.resolve(ScopeContext.of(null, null, "gpt", null))).map(EffectiveQuota::ruleId)
                .contains("r-model");
    }

    @Test
    void 未命中规则时取系统默认额度() {
        Optional<EffectiveQuota> q = resolver.resolve(ScopeContext.of("nobody", null, null, null));
        assertThat(q).map(EffectiveQuota::limitMinor).contains(1_000_000L);
    }

    @Test
    void 团队限额分摊到成员_按成员数向上取整() {
        rules.saveRule(new QuotaRule("r-team", ScopeType.TEAM, "team1", null, 100, "CNY", true), "op", "tr");
        rules.addMember("team1", "u1");
        rules.addMember("team1", "u2");
        rules.addMember("team1", "u3");
        EffectiveQuota q = resolver.resolve(ScopeContext.of("u1", "team1", null, null)).orElseThrow();
        assertThat(q.limitMinor()).isEqualTo(34L); // ceil(100/3)
        assertThat(q.accountScopeType()).isEqualTo(ScopeType.USER);
        assertThat(q.accountScopeId()).isEqualTo("u1");
    }

    @Test
    void 非分摊团队规则使用共享池账户() {
        rules.saveRule(new QuotaRule("r-team", ScopeType.TEAM, "team1", null, 100, "CNY", false), "op", "tr");
        EffectiveQuota q = resolver.resolve(ScopeContext.of("u1", "team1", null, null)).orElseThrow();
        assertThat(q.accountScopeType()).isEqualTo(ScopeType.TEAM);
        assertThat(q.accountScopeId()).isEqualTo("team1");
        assertThat(q.limitMinor()).isEqualTo(100L);
    }

    @Test
    void 价格发布自动关闭前一版本窗口_旧版本保留可查() {
        Sku sku = new Sku("gpt", UsageType.TOKEN);
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-01-02T00:00:00Z");
        catalog.publish(sku, 1L, "CNY", t0);
        catalog.publish(sku, 2L, "CNY", t1);

        PriceSnapshot atT0 = catalog.resolve(sku, t0.plusSeconds(60)).orElseThrow();
        PriceSnapshot atT1 = catalog.resolve(sku, t1.plusSeconds(60)).orElseThrow();
        assertThat(atT0.version()).isEqualTo(1L);
        assertThat(atT0.pricePerUnitMinor()).isEqualTo(1L);
        assertThat(atT1.version()).isEqualTo(2L);
        assertThat(atT1.pricePerUnitMinor()).isEqualTo(2L);
        // 旧版本保留可查（审计/回溯）
        assertThat(catalog.loadVersions(sku)).hasSize(2);
        assertThat(catalog.loadVersion(atT0.priceVersionId()).orElseThrow().getEffectiveTo()).isEqualTo(t1);
    }

    @Test
    void Q5_请求发起于v3生效期_结算发生在v4生效期_仍按v3快照结算() {
        Sku sku = new Sku("gpt", UsageType.TOKEN);
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-01-02T00:00:00Z");
        Instant v3From = Instant.parse("2026-01-03T00:00:00Z");
        Instant v4From = Instant.parse("2026-01-04T00:00:00Z");
        catalog.publish(sku, 1L, "CNY", t0);
        catalog.publish(sku, 2L, "CNY", t1);
        catalog.publish(sku, 3L, "CNY", v3From);
        catalog.publish(sku, 4L, "CNY", v4From);
        // 请求发起于 v3 生效期 → 快照固化 v3；结算发生在 v4 生效期，仍按 v3 单价结算
        PriceSnapshot requestTime = catalog.resolve(sku, v3From.plusSeconds(60)).orElseThrow();
        assertThat(requestTime.version()).isEqualTo(3L);
        assertThat(requestTime.pricePerUnitMinor()).isEqualTo(3L);
        PriceVersion snapshotAtSettle = catalog.loadVersion(requestTime.priceVersionId()).orElseThrow();
        assertThat(snapshotAtSettle.getVersion()).isEqualTo(3L);
        assertThat(snapshotAtSettle.getPricePerUnitMinor()).isEqualTo(3L);
        assertThat(catalog.loadVersions(sku)).hasSize(4); // 旧版本全部保留可查
    }
}
