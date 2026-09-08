package io.quotapilot.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.quotapilot.alert.domain.Alert;
import io.quotapilot.alert.domain.AlertEmitterPort;
import io.quotapilot.common.TimeService;
import io.quotapilot.ledger.domain.AccountPort;
import io.quotapilot.ledger.domain.AccountStatus;
import io.quotapilot.ledger.domain.ScopeType;
import io.quotapilot.pricing.domain.PriceVersion;
import io.quotapilot.pricing.domain.PriceVersionPort;
import io.quotapilot.quota.domain.QuotaRule;
import io.quotapilot.quota.domain.QuotaRulePort;

/**
 * [测试支撑] 可变时钟 + 记录型告警出口 + 内存版账户/规则/价格假实现。
 */
public class Support {

    public static class MutableTime implements TimeService {
        public Instant now = Instant.parse("2026-09-08T00:00:00Z");

        public void advanceSeconds(long s) {
            now = now.plusSeconds(s);
        }

        @Override
        public Instant now() {
            return now;
        }
    }

    public static class RecordingAlerts implements AlertEmitterPort {
        public final List<Alert> alerts = new ArrayList<>();

        @Override
        public void emit(Alert alert) {
            alerts.add(alert);
        }
    }

    public static class FakeAccounts implements AccountPort {
        public final Map<String, AccountSnapshot> byId = new LinkedHashMap<>();

        @Override
        public AccountSnapshot getOrCreate(ScopeType scopeType, String scopeId, long initialLimitMinor, String currency) {
            return byId.values().stream()
                    .filter(a -> a.scopeType() == scopeType && a.scopeId().equals(scopeId)).findFirst()
                    .orElseGet(() -> {
                        String id = "acct-" + scopeType + "-" + scopeId;
                        AccountSnapshot s = new AccountSnapshot(id, scopeType, scopeId, initialLimitMinor,
                                currency, AccountStatus.ACTIVE);
                        byId.put(id, s);
                        return s;
                    });
        }

        @Override
        public java.util.Optional<AccountSnapshot> find(String accountId) {
            return java.util.Optional.ofNullable(byId.get(accountId));
        }

        @Override
        public java.util.Optional<AccountSnapshot> findByScope(ScopeType scopeType, String scopeId) {
            return byId.values().stream()
                    .filter(a -> a.scopeType() == scopeType && a.scopeId().equals(scopeId)).findFirst();
        }

        @Override
        public void updateStatus(String accountId, AccountStatus status) {
            AccountSnapshot a = byId.get(accountId);
            byId.put(accountId, new AccountSnapshot(a.accountId(), a.scopeType(), a.scopeId(), a.quotaLimitMinor(),
                    a.currency(), status));
        }

        @Override
        public void updateLimit(String accountId, long newLimitMinor) {
            AccountSnapshot a = byId.get(accountId);
            byId.put(accountId, new AccountSnapshot(a.accountId(), a.scopeType(), a.scopeId(), newLimitMinor,
                    a.currency(), a.status()));
        }

        @Override
        public List<String> allAccountIds() {
            return List.copyOf(byId.keySet());
        }

        @Override
        public long settledMinor(String accountId) {
            return 0;
        }
    }

    public static class FakeRules implements QuotaRulePort {
        public final Map<String, QuotaRule> rules = new LinkedHashMap<>();
        public final Map<String, java.util.Set<String>> teamMembers = new LinkedHashMap<>();

        @Override
        public QuotaRule saveRule(QuotaRule rule, String operator, String traceId) {
            rules.put(rule.ruleId(), rule);
            return rule;
        }

        @Override
        public List<QuotaRule> listActive() {
            return List.copyOf(rules.values());
        }

        @Override
        public long countTeamMembers(String teamId) {
            return teamMembers.getOrDefault(teamId, java.util.Set.of()).size();
        }

        @Override
        public void addMember(String teamId, String userId) {
            teamMembers.computeIfAbsent(teamId, k -> new java.util.HashSet<>()).add(userId);
        }
    }

    public static class FakePrices implements PriceVersionPort {
        public final Map<String, PriceVersion> versions = new LinkedHashMap<>();

        @Override
        public void save(PriceVersion version) {
            versions.put(version.getPriceVersionId(), version);
        }

        @Override
        public java.util.Optional<PriceVersion> findById(String priceVersionId) {
            return java.util.Optional.ofNullable(versions.get(priceVersionId));
        }

        @Override
        public List<PriceVersion> findBySku(String skuKey) {
            return versions.values().stream().filter(v -> v.getSkuKey().equals(skuKey)).toList();
        }
    }
}
