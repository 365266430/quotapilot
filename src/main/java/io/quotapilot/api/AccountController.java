package io.quotapilot.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import io.quotapilot.dashboard.domain.AccountBalanceView;
import io.quotapilot.dashboard.domain.DashboardService;

/** [M9] 面板 API：GET /v1/accounts/{id}/balance（limit/settled/held/exposure/available）。 */
@RestController
public class AccountController {

    private final DashboardService dashboardService;

    public AccountController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/v1/accounts/{id}/balance")
    public AccountBalanceView balance(@PathVariable("id") String accountId) {
        return dashboardService.balance(accountId);
    }
}
