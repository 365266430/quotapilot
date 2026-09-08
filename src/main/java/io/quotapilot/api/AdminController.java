package io.quotapilot.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.quotapilot.ledger.domain.AccountPort;
import io.quotapilot.ledger.domain.AccountStatus;
import io.quotapilot.ledger.domain.ExposureRecord;
import io.quotapilot.ledger.domain.ExposureRepositoryPort;
import io.quotapilot.quota.domain.QuotaService;
import io.quotapilot.reconcile.domain.ReconciliationService;
import io.quotapilot.settlement.domain.SweeperService;
import io.quotapilot.supplier.domain.SupplierChargeStorePort;

/** [M5/M9/M11] 管理 API：手工对账、手工 sweep、硬止损、敞口与告警查询、供应商侧账本。 */
@RestController
public class AdminController {

    private final ReconciliationService reconciliationService;
    private final SweeperService sweeperService;
    private final QuotaService quotaService;
    private final ExposureRepositoryPort exposureRepo;
    private final SupplierChargeStorePort supplierCharges;
    private final io.quotapilot.dashboard.domain.DashboardService dashboardService;
    private final io.quotapilot.ratelimit.domain.RateLimitRulePort rateLimitRulePort;

    public AdminController(ReconciliationService reconciliationService, SweeperService sweeperService,
                           QuotaService quotaService, ExposureRepositoryPort exposureRepo,
                           SupplierChargeStorePort supplierCharges,
                           io.quotapilot.dashboard.domain.DashboardService dashboardService,
                           io.quotapilot.ratelimit.domain.RateLimitRulePort rateLimitRulePort) {
        this.reconciliationService = reconciliationService;
        this.sweeperService = sweeperService;
        this.quotaService = quotaService;
        this.exposureRepo = exposureRepo;
        this.supplierCharges = supplierCharges;
        this.dashboardService = dashboardService;
        this.rateLimitRulePort = rateLimitRulePort;
    }

    public record ReconcileReq(String accountId) {}

    @PostMapping("/v1/admin/reconcile")
    public ReconciliationService.ReconciliationReport reconcile(@RequestBody(required = false) ReconcileReq req) {
        return reconciliationService.reconcile(req == null ? null : req.accountId());
    }

    @PostMapping("/v1/admin/sweep")
    public Map<String, Integer> sweep() {
        int holds = sweeperService.sweepExpiredReservations();
        int exposures = sweeperService.sweepExpiredExposures();
        return Map.of("expiredReservations", holds, "closedExposures", exposures);
    }

    public record StatusReq(String status) {}

    @PostMapping("/v1/admin/accounts/{id}/status")
    public Map<String, String> setStatus(@org.springframework.web.bind.annotation.PathVariable("id") String accountId,
                                         @RequestBody StatusReq req) {
        quotaService.setAccountStatus(accountId, AccountStatus.valueOf(req.status()));
        return Map.of("accountId", accountId, "status", req.status());
    }

    @GetMapping("/v1/admin/exposures")
    public List<ExposureRecord> exposures(@RequestParam(name = "accountId", required = false) String accountId) {
        return accountId == null ? List.of() : exposureRepo.findOpenByAccount(accountId);
    }

    @GetMapping("/v1/admin/supplier-ledger")
    public List<SupplierChargeStorePort.SupplierCharge> supplierLedger(
            @RequestParam(name = "accountId", required = false) String accountId) {
        return accountId == null ? supplierCharges.listAll() : supplierCharges.findByAccount(accountId);
    }

    @GetMapping("/v1/admin/alerts")
    public List<io.quotapilot.alert.domain.Alert> alerts() {
        return dashboardService.recentAlerts(50);
    }

    // ---- M7 限流规则 ----

    public record RateLimitReq(long requestsPerSecond, long tokensPerMinute, long billingUnitsPerMinute) {}

    @org.springframework.web.bind.annotation.PostMapping("/v1/admin/rate-limits")
    public io.quotapilot.ratelimit.domain.RateLimitRule setRateLimit(
            @org.springframework.web.bind.annotation.PathVariable("id") String accountId,
            @RequestBody RateLimitReq req) {
        return rateLimitRulePort.save(new io.quotapilot.ratelimit.domain.RateLimitRule(accountId,
                req.requestsPerSecond(), req.tokensPerMinute(), req.billingUnitsPerMinute()));
    }

    @org.springframework.web.bind.annotation.GetMapping("/v1/admin/rate-limits/{id}")
    public io.quotapilot.ratelimit.domain.RateLimitRule getRateLimit(
            @org.springframework.web.bind.annotation.PathVariable("id") String accountId) {
        return rateLimitRulePort.findByAccount(accountId).orElseThrow(
                () -> new io.quotapilot.common.DomainExceptions.NotFound("限流规则不存在: " + accountId));
    }
}
