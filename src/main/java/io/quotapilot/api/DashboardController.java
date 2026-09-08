package io.quotapilot.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.quotapilot.alert.domain.Alert;
import io.quotapilot.dashboard.domain.AccountBalanceView;
import io.quotapilot.dashboard.domain.DashboardService;

/** [M9] 面板 API：GET /v1/dashboards?scope={accountId}（四值 + 告警）。 */
@RestController
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/v1/dashboards")
    public Map<String, Object> dashboard(@RequestParam("scope") String accountId) {
        AccountBalanceView balance = dashboardService.balance(accountId);
        List<Alert> alerts = dashboardService.recentAlerts(20);
        return Map.of("balance", balance, "recentAlerts", alerts);
    }
}
