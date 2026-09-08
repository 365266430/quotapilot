package io.quotapilot.supplier.openai;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.quotapilot.gateway.domain.GatewayOrchestrator;
import io.quotapilot.gateway.domain.GatewayRequest;
import io.quotapilot.gateway.domain.GatewayResult;
import io.quotapilot.ledger.domain.ScopeType;
import io.quotapilot.pricing.domain.PriceCatalog;
import io.quotapilot.pricing.domain.Sku;
import io.quotapilot.quota.domain.QuotaRule;
import io.quotapilot.quota.domain.QuotaRulePort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [M10/V1.1] 真实 api.openai.com 联调测试。
 * 仅当设置环境变量 QUOTAPILOT_OPENAI_API_KEY 时执行（CI/本地凭据就绪场景），否则跳过。
 * 消费上限极小（declaredEstimatedUnits=16，max_tokens=16）以控制真实费用。
 */
@EnabledIfEnvironmentVariable(named = "QUOTAPILOT_OPENAI_API_KEY", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenAiRealApiIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("quotapilot.suppliers.openai.enabled", () -> "true");
        registry.add("quotapilot.suppliers.openai.api-key", () -> System.getenv("QUOTAPILOT_OPENAI_API_KEY"));
        String base = System.getenv("QUOTAPILOT_OPENAI_BASE_URL");
        if (base != null && !base.isBlank()) {
            registry.add("quotapilot.suppliers.openai.base-url", () -> base);
        }
    }

    @Autowired GatewayOrchestrator orchestrator;
    @Autowired PriceCatalog priceCatalog;
    @Autowired QuotaRulePort rulePort;
    @Autowired io.quotapilot.ledger.domain.AccountPort accountPort;

    @Test
    void 真实协议_端到端_预留调用结算() {
        String user = "real-" + UUID.randomUUID();
        String model = System.getenv().getOrDefault("QUOTAPILOT_OPENAI_MODEL", "gpt-4o-mini");
        rulePort.saveRule(new QuotaRule("rule-" + user, ScopeType.USER, user, null, 1_000_000L, "CNY", false),
                "test", "tr");
        priceCatalog.publish(new Sku(model, io.quotapilot.pricing.domain.UsageType.TOKEN), 1L, "CNY", Instant.now());
        accountPort.getOrCreate(ScopeType.USER, user, 1_000_000L, "CNY");

        String rid = "real-" + UUID.randomUUID();
        GatewayResult result = orchestrator.execute(new GatewayRequest(rid, user, null, null, model, 16L,
                "用一句话介绍你自己", false, 0, null, "openai"));

        assertThat(result.status()).isEqualTo(GatewayResult.SUCCEEDED);
        assertThat(result.usageUnits()).isPositive();          // 真实 usage 解析
        assertThat(result.chargedMinor()).isEqualTo(result.usageUnits());
    }
}
