package io.quotapilot.quota.domain;

import java.util.Optional;

import io.quotapilot.common.Amounts;
import io.quotapilot.common.TimeService;
import io.quotapilot.ledger.domain.AccountPort;
import io.quotapilot.reserve.domain.ReservationGatePort;

/**
 * [M1] 额度配置应用服务：规则变更（同事务审计）+ 硬止损 BLOCKED + 团队成员管理。
 * 变更不立即生效于在途请求（在途按发起时快照执行，P5）；新限额经 Redis reconcile 即时生效于新请求。
 */
public class QuotaService {

    private final QuotaRulePort rulePort;
    private final AccountPort accountPort;
    private final ReservationGatePort gate;
    private final io.quotapilot.ledger.domain.ReservationRepositoryPort reservationRepo;
    private final TimeService time;

    public QuotaService(QuotaRulePort rulePort, AccountPort accountPort, ReservationGatePort gate,
                        io.quotapilot.ledger.domain.ReservationRepositoryPort reservationRepo, TimeService time) {
        this.rulePort = rulePort;
        this.accountPort = accountPort;
        this.gate = gate;
        this.reservationRepo = reservationRepo;
        this.time = time;
    }

    public QuotaRule setRule(QuotaRule rule, String operator, String traceId) {
        QuotaRule saved = rulePort.saveRule(rule, operator, traceId);
        if (!saved.sharedAmongMembers()) {
            // 存量共享池账户同步新限额（新请求立即生效；在途请求仍按其快照执行）
            accountPort.findByScope(saved.scopeType(), saved.scopeId()).ifPresent(acct -> {
                if (acct.quotaLimitMinor() != saved.quotaLimitMinor()) {
                    accountPort.updateLimit(acct.accountId(), saved.quotaLimitMinor());
                    gate.reconcile(acct.accountId(), saved.quotaLimitMinor(),
                            accountPort.settledMinor(acct.accountId()),
                            reservationRepo.sumActiveHolds(acct.accountId()));
                }
            });
        }
        return saved;
    }

    private long settledOf(String accountId) {
        return accountPort.settledMinor(accountId);
    }

    public void addTeamMember(String teamId, String userId) {
        rulePort.addMember(teamId, userId);
        migrateSharedTeamLimits(teamId);
    }

    /**
     * [V1.1] 团队分摊限额迁移：成员数变化 → 重算所有成员账户限额并同步 Redis（新请求立即生效；
     * 在途请求仍按发起时快照执行，P5）。
     */
    private void migrateSharedTeamLimits(String teamId) {
        rulePort.listActive().stream()
                .filter(r -> (r.scopeType() == io.quotapilot.ledger.domain.ScopeType.TEAM
                        || r.scopeType() == io.quotapilot.ledger.domain.ScopeType.TEAM_MODEL)
                        && teamId.equals(r.scopeId()) && r.sharedAmongMembers())
                .findFirst()
                .ifPresent(rule -> {
                    long perMember = perMemberLimit(teamId, rule.quotaLimitMinor());
                    for (String member : rulePort.listMembers(teamId)) {
                        accountPort.findByScope(io.quotapilot.ledger.domain.ScopeType.USER, member)
                                .ifPresent(acct -> {
                                    if (acct.quotaLimitMinor() != perMember) {
                                        accountPort.updateLimit(acct.accountId(), perMember);
                                        gate.reconcile(acct.accountId(), perMember,
                                                accountPort.settledMinor(acct.accountId()),
                                                reservationRepo.sumActiveHolds(acct.accountId()));
                                    }
                                });
                    }
                });
    }

    /** 团队限额分摊到成员：限额 = 团队总额 / 成员数（向上取整）。 */
    public long perMemberLimit(String teamId, long teamLimitMinor) {
        long members = Math.max(1, rulePort.countTeamMembers(teamId));
        return Amounts.avgCeil(teamLimitMinor, members);
    }

    public void setAccountStatus(String accountId, io.quotapilot.ledger.domain.AccountStatus status) {
        accountPort.updateStatus(accountId, status);
    }

    public Optional<AccountPort.AccountSnapshot> findAccountByScope(io.quotapilot.ledger.domain.ScopeType type,
                                                                    String scopeId) {
        return accountPort.findByScope(type, scopeId);
    }
}
