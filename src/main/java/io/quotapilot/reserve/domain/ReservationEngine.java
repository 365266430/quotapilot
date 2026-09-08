package io.quotapilot.reserve.domain;

import java.time.Instant;
import java.util.Optional;

import io.quotapilot.common.Amounts;
import io.quotapilot.common.DomainExceptions;
import io.quotapilot.common.TimeService;
import io.quotapilot.ledger.domain.AccountPort;
import io.quotapilot.ledger.domain.Reservation;
import io.quotapilot.ledger.domain.ReservationRepositoryPort;
import io.quotapilot.pricing.domain.PriceCatalog;
import io.quotapilot.pricing.domain.PriceSnapshot;
import io.quotapilot.pricing.domain.Sku;
import io.quotapilot.quota.domain.EffectiveQuota;
import io.quotapilot.quota.domain.QuotaPolicyResolver;
import io.quotapilot.quota.domain.ScopeContext;
import io.quotapilot.supplier.domain.SupplierSpi;

/**
 * [M3] 预留引擎（纯领域服务，端口注入）。
 * 主流程（规范 M3）：解析额度与价格快照 → 估算成本 → Redis Lua 原子预留 →
 * DB 同事务落账（Reservation + HOLD 流水 + Outbox）→ 返回 holdId。
 * 可靠性：Redis 成功但 DB 失败 → 立即补偿释放 + sweeper 兜底收敛（Q1/P7）。
 */
public class ReservationEngine {

    private final QuotaPolicyResolver quotaResolver;
    private final AccountPort accountPort;
    private final PriceCatalog priceCatalog;
    private final ReservationGatePort gate;
    private final LedgerPortAccess ledgerAccess;
    private final ReservationRepositoryPort reservationRepo;
    private final Optional<SupplierSpi> supplier;
    private final TimeService time;
    private final long defaultTtlSeconds;
    private final String defaultCurrency;

    public ReservationEngine(QuotaPolicyResolver quotaResolver, AccountPort accountPort, PriceCatalog priceCatalog,
                             ReservationGatePort gate, LedgerPortAccess ledgerAccess,
                             ReservationRepositoryPort reservationRepo, Optional<SupplierSpi> supplier,
                             TimeService time, long defaultTtlSeconds, String defaultCurrency) {
        this.quotaResolver = quotaResolver;
        this.accountPort = accountPort;
        this.priceCatalog = priceCatalog;
        this.gate = gate;
        this.ledgerAccess = ledgerAccess;
        this.reservationRepo = reservationRepo;
        this.supplier = supplier;
        this.time = time;
        this.defaultTtlSeconds = defaultTtlSeconds;
        this.defaultCurrency = defaultCurrency;
    }

    public ReserveResult reserve(ReserveCommand cmd) {
        Instant now = time.now();
        // 1. 解析额度（M1）与账户
        ScopeContext ctx = new ScopeContext(cmd.scope().userId(), cmd.scope().teamId(), cmd.skuModel(), cmd.scope().taskId());
        EffectiveQuota quota = quotaResolver.resolve(ctx)
                .orElseThrow(() -> new DomainExceptions.NoQuotaConfigured(ctx.toString()));
        AccountPort.AccountSnapshot account = accountPort.getOrCreate(quota.accountScopeType(), quota.accountScopeId(),
                quota.limitMinor(), quota.currency() == null ? defaultCurrency : quota.currency());
        if (account.status() == io.quotapilot.ledger.domain.AccountStatus.BLOCKED) {
            throw new DomainExceptions.AccountBlocked(account.accountId());
        }
        // 2. 价格快照（P5：以发起时刻生效版本固化）
        Sku sku = new Sku(cmd.skuModel(), io.quotapilot.pricing.domain.UsageType.TOKEN);
        PriceSnapshot snapshot = priceCatalog.resolve(sku, now)
                .orElseThrow(() -> new DomainExceptions.PricingUnavailable(sku.key()));
        // 3. 估算成本（estimate 语义，P1）：调用方声明上限优先，否则供应商估计
        long units = cmd.declaredEstimatedUnits() != null
                ? cmd.declaredEstimatedUnits()
                : supplier.map(s -> s.estimateUsage(cmd.skuModel()).estimatedUnits())
                        .orElseThrow(() -> new DomainExceptions.HoldFailed("缺少估计用量且供应商无法提供", null));
        if (units <= 0) {
            throw new DomainExceptions.HoldFailed("估计用量必须为正: " + units, null);
        }
        long estimate = Amounts.exactMultiply(units, snapshot.pricePerUnitMinor());
        // 4. Redis 原子门（P4：检查+扣减一步完成）
        ReservationGatePort.GateResult gateResult = gate.tryReserve(account.accountId(), estimate);
        if (!gateResult.ok()) {
            throw new DomainExceptions.QuotaExceeded(account.accountId(), estimate, gateResult.availableMinor());
        }
        // 5. DB 同事务落账（P3/P6）；失败立即补偿释放 Redis，sweeper 兜底（Q1）
        long ttl = cmd.ttlSeconds() > 0 ? cmd.ttlSeconds() : defaultTtlSeconds;
        try {
            LedgerPortAccess.HoldOutcome outcome = ledgerAccess.doHold(new LedgerPortAccess.HoldParams(
                    cmd.requestId(), account.accountId(), estimate, snapshot.priceVersionId(),
                    now.plusSeconds(ttl), cmd.traceId()));
            if (outcome.duplicate()) {
                // 并发同 requestId：回滚本次多扣的 Redis held，返回首次结果
                gate.releaseHold(account.accountId(), estimate);
                Reservation existing = reservationRepo.findByRequestId(cmd.requestId()).orElse(null);
                if (existing == null) {
                    throw new DomainExceptions.HoldFailed("重复预留但找不到首次记录: " + cmd.requestId(), null);
                }
                return new ReserveResult(cmd.requestId(), existing.getHoldId(), account.accountId(),
                        existing.getReservedAmountMinor(), units, existing.getPriceVersionId(),
                        existing.getExpiresAt(), true);
            }
            return new ReserveResult(cmd.requestId(), outcome.holdId(), account.accountId(), estimate, units,
                    snapshot.priceVersionId(), now.plusSeconds(ttl), false);
        } catch (RuntimeException e) {
            gate.releaseHold(account.accountId(), estimate);
            if (e instanceof DomainExceptions.DuplicateRequest dup) {
                Reservation existing = reservationRepo.findByRequestId(cmd.requestId()).orElse(null);
                if (existing != null) {
                    return new ReserveResult(cmd.requestId(), existing.getHoldId(), account.accountId(),
                            existing.getReservedAmountMinor(), units, existing.getPriceVersionId(),
                            existing.getExpiresAt(), true);
                }
            }
            throw new DomainExceptions.HoldFailed("预留落库失败，已补偿释放 Redis: " + cmd.requestId(), e);
        }
    }

    /**
     * 引擎对账本内核的依赖收口（避免领域服务直接依赖具体端口实现的事务语义）。
     */
    public interface LedgerPortAccess {

        HoldOutcome doHold(HoldParams params);

        record HoldParams(String requestId, String accountId, long amountMinor, String priceVersionId,
                          Instant expiresAt, String traceId) {}

        record HoldOutcome(String holdId, boolean duplicate) {}
    }
}
