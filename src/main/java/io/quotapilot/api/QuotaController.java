package io.quotapilot.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.quotapilot.common.TraceIdHolder;
import io.quotapilot.ledger.domain.ScopeType;
import io.quotapilot.quota.domain.QuotaRule;
import io.quotapilot.quota.domain.QuotaService;

/** [M1] 额度配置 API：POST /v1/quotas；团队成员管理；规范 §8。 */
@RestController
@RequestMapping("/v1/quotas")
public class QuotaController {

    private final QuotaService quotaService;

    public QuotaController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    public record SetRuleReq(String ruleId, String scopeType, String scopeId, String model, long quotaLimitMinor,
                             String currency, boolean sharedAmongMembers) {}

    @PostMapping
    public QuotaRule setRule(@org.springframework.web.bind.annotation.RequestBody SetRuleReq req) {
        QuotaRule rule = new QuotaRule(req.ruleId() == null
                ? java.util.UUID.randomUUID().toString() : req.ruleId(),
                ScopeType.valueOf(req.scopeType()), req.scopeId(), req.model(), req.quotaLimitMinor(),
                req.currency() == null ? "CNY" : req.currency(), req.sharedAmongMembers());
        return quotaService.setRule(rule, "admin", TraceIdHolder.get());
    }

    public record AddMemberReq(String userId) {}

    @PostMapping("/{teamId}/members")
    public java.util.Map<String, String> addMember(@org.springframework.web.bind.annotation.PathVariable String teamId,
                                                   @org.springframework.web.bind.annotation.RequestBody AddMemberReq req) {
        quotaService.addTeamMember(teamId, req.userId());
        return java.util.Map.of("teamId", teamId, "userId", req.userId());
    }
}
