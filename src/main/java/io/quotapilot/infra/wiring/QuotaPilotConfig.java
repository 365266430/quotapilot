package io.quotapilot.infra.wiring;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.quotapilot.common.TimeService;
import io.quotapilot.dashboard.domain.DashboardService;
import io.quotapilot.gateway.domain.GatewayOrchestrator;
import io.quotapilot.gateway.domain.RequestQueryService;
import io.quotapilot.infra.persistence.AccountAdapter;
import io.quotapilot.infra.persistence.AlertStoreAdapter;
import io.quotapilot.infra.persistence.ExposureAdapter;
import io.quotapilot.infra.persistence.LedgerAdapter;
import io.quotapilot.infra.persistence.LedgerQueryAdapter;
import io.quotapilot.infra.persistence.PriceVersionAdapter;
import io.quotapilot.infra.persistence.QuotaRuleAdapter;
import io.quotapilot.infra.persistence.ReservationAdapter;
import io.quotapilot.infra.persistence.SupplierChargeAdapter;
import io.quotapilot.infra.persistence.UsageEventAdapter;
import io.quotapilot.infra.redis.RedisReservationGate;
import io.quotapilot.metering.domain.CallbackService;
import io.quotapilot.quota.domain.DefaultQuotaPolicyResolver;
import io.quotapilot.quota.domain.QuotaService;
import io.quotapilot.reconcile.domain.ReconciliationService;
import io.quotapilot.reserve.domain.ReservationEngine;
import io.quotapilot.settlement.domain.SettlementService;
import io.quotapilot.settlement.domain.SweeperService;
import io.quotapilot.supplier.mock.MockSupplier;

/**
 * [M11] 领域服务装配：纯领域类以 @Bean 接入 Spring（领域层零 Spring 依赖，规范 §7）。
 */
@Configuration
public class QuotaPilotConfig {

    @Bean
    public io.quotapilot.pricing.domain.PriceCatalog priceCatalog(PriceVersionAdapter port) {
        return new io.quotapilot.pricing.domain.PriceCatalog(port);
    }

    @Bean
    public DefaultQuotaPolicyResolver quotaPolicyResolver(QuotaRuleAdapter rulePort,
                                                          @Value("${quotapilot.system-default-limit-minor:100000000}") long systemDefaultLimitMinor,
                                                          @Value("${quotapilot.default-currency:CNY}") String defaultCurrency) {
        return new DefaultQuotaPolicyResolver(rulePort, systemDefaultLimitMinor, defaultCurrency);
    }

    @Bean
    public ReservationEngine reservationEngine(DefaultQuotaPolicyResolver resolver, AccountAdapter accountPort,
                                               io.quotapilot.pricing.domain.PriceCatalog priceCatalog,
                                               RedisReservationGate gate, LedgerAdapter ledgerPort,
                                               ReservationAdapter reservationRepo, MockSupplier supplier,
                                               TimeService time,
                                               @Value("${quotapilot.reserve-ttl-seconds:120}") long ttlSeconds,
                                               @Value("${quotapilot.default-currency:CNY}") String currency) {
        return new ReservationEngine(resolver, accountPort, priceCatalog, gate, ledgerPort,
                reservationRepo, Optional.of(supplier), time, ttlSeconds, currency);
    }

    @Bean
    public SettlementService settlementService(ReservationAdapter reservationRepo, ExposureAdapter exposureRepo,
                                               io.quotapilot.pricing.domain.PriceCatalog priceCatalog,
                                               LedgerAdapter ledgerPort, LedgerQueryAdapter ledgerQuery,
                                               RedisReservationGate gate,
                                               io.quotapilot.alert.domain.AlertEmitterPort alerts, TimeService time,
                                               @Value("${quotapilot.exposure-grace-seconds:300}") long graceSeconds) {
        return new SettlementService(reservationRepo, exposureRepo, priceCatalog, ledgerPort, ledgerQuery, gate,
                alerts, time, graceSeconds);
    }

    @Bean
    public SweeperService sweeperService(ReservationAdapter reservationRepo, ExposureAdapter exposureRepo,
                                         SettlementService settlementService, LedgerAdapter ledgerPort,
                                         RedisReservationGate gate, TimeService time) {
        return new SweeperService(reservationRepo, exposureRepo, settlementService, ledgerPort, gate, time);
    }

    @Bean
    public io.quotapilot.supplier.domain.SupplierRegistry supplierRegistry(MockSupplier mockSupplier) {
        return new io.quotapilot.supplier.domain.SupplierRegistry(java.util.Map.of(
                mockSupplier.name(), mockSupplier));
    }

    @Bean
    public GatewayOrchestrator gatewayOrchestrator(ReservationEngine engine, SettlementService settlementService,
                                                   io.quotapilot.supplier.domain.SupplierRegistry registry,
                                                   UsageEventAdapter usageEvents,
                                                   ExposureAdapter exposureRepo, TimeService time) {
        return new GatewayOrchestrator(engine, settlementService, registry, usageEvents, exposureRepo, time);
    }

    @Bean
    public CallbackService callbackService(UsageEventAdapter usageEvents, SettlementService settlementService,
                                           TimeService time) {
        return new CallbackService(usageEvents, settlementService, time);
    }

    @Bean
    public ReconciliationService reconciliationService(SupplierChargeAdapter supplierCharges,
                                                       ReservationAdapter reservationRepo, LedgerAdapter ledgerPort,
                                                       LedgerQueryAdapter ledgerQuery,
                                                       SettlementService settlementService,
                                                       UsageEventAdapter usageEvents,
                                                       io.quotapilot.alert.domain.AlertEmitterPort alerts,
                                                       TimeService time) {
        return new ReconciliationService(supplierCharges, reservationRepo, ledgerPort, ledgerQuery,
                settlementService, usageEvents, alerts, time);
    }

    @Bean
    public DashboardService dashboardService(AccountAdapter accountPort, LedgerQueryAdapter ledgerQuery,
                                             ReservationAdapter reservationRepo, ExposureAdapter exposureRepo,
                                             io.quotapilot.alert.domain.AlertEmitterPort alerts,
                                             AlertStoreAdapter alertStore, TimeService time,
                                             @Value("${quotapilot.low-available-warn-pct:20}") long lowPct,
                                             @Value("${quotapilot.exposure-backlog-threshold:10}") long backlog,
                                             @Value("${quotapilot.alert-cooldown-seconds:60}") long cooldown) {
        return new DashboardService(accountPort, ledgerQuery, reservationRepo, exposureRepo, alerts, alertStore,
                time, lowPct, backlog, cooldown);
    }

    @Bean
    public MockSupplier mockSupplier(SupplierChargeAdapter chargeStore,
                                     io.quotapilot.infra.supplier.CallbackSenderAdapter callbackSender,
                                     TimeService time,
                                     @Value("${quotapilot.mock.default-delay-millis:0}") long delayMillis,
                                     @Value("${quotapilot.mock.default-drift-percent:0}") long driftPercent) {
        return new MockSupplier(chargeStore, callbackSender, time, delayMillis, driftPercent);
    }

    @Bean
    public QuotaService quotaService(QuotaRuleAdapter rulePort, AccountAdapter accountPort,
                                     RedisReservationGate gate, ReservationAdapter reservationRepo, TimeService time) {
        return new QuotaService(rulePort, accountPort, gate, reservationRepo, time);
    }

    @Bean
    public RequestQueryService requestQueryService(ReservationAdapter reservationRepo, LedgerQueryAdapter ledgerQuery,
                                                   ExposureAdapter exposureRepo) {
        return new RequestQueryService(reservationRepo, ledgerQuery, exposureRepo);
    }
}
