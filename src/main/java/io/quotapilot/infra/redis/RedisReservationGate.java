package io.quotapilot.infra.redis;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import io.quotapilot.common.DomainExceptions;
import io.quotapilot.ledger.domain.AccountPort;
import io.quotapilot.ledger.domain.LedgerEntryType;
import io.quotapilot.ledger.domain.LedgerQueryPort;
import io.quotapilot.ledger.domain.ReservationRepositoryPort;
import io.quotapilot.reserve.domain.ReservationGatePort;

/**
 * [M3/P4/Q1] Redis Lua 预留原子门：「检查+扣减」单脚本原子完成，杜绝先查后扣竞态。
 * 账本哈希字段：limit / settled / held（available = limit − settled − held）。
 * 首次触达账户时以 DB 权威口径惰性初始化（脚本内 EXISTS 判定，避免每请求回源）。
 * DB 为权威账本，Redis 丢失/漂移由 sweeper reconcile 重建（P7）。
 */
@Component
public class RedisReservationGate implements ReservationGatePort {

    private static final String KEY_PREFIX = "account:balance:";

    /** 原子预留：键不存在时用 ARGV[2..4]（DB 口径）初始化，再判断 available >= estimate。 */
    private static final String RESERVE_LUA = """
            local k = KEYS[1]
            if redis.call('EXISTS', k) == 0 then
              redis.call('HSET', k, 'limit', ARGV[2], 'settled', ARGV[3], 'held', ARGV[4])
            end
            local est = tonumber(ARGV[1])
            local held = tonumber(redis.call('HGET', k, 'held') or '0')
            local settled = tonumber(redis.call('HGET', k, 'settled') or '0')
            local limit = tonumber(redis.call('HGET', k, 'limit') or '0')
            if (limit - settled - held) >= est then
              redis.call('HSET', k, 'held', held + est)
              return 1
            end
            return 0
            """;

    private static final String RELEASE_LUA = """
            local k = KEYS[1]
            local held = tonumber(redis.call('HGET', k, 'held') or '0')
            local nh = held - tonumber(ARGV[1])
            if nh < 0 then nh = 0 end
            redis.call('HSET', k, 'held', nh)
            return 1
            """;

    private static final String SETTLE_LUA = """
            local k = KEYS[1]
            local held = tonumber(redis.call('HGET', k, 'held') or '0')
            local settled = tonumber(redis.call('HGET', k, 'settled') or '0')
            local nh = held - tonumber(ARGV[1])
            if nh < 0 then nh = 0 end
            redis.call('HSET', k, 'held', nh, 'settled', settled + tonumber(ARGV[2]))
            return 1
            """;

    private static final String LATE_SETTLE_LUA = """
            local k = KEYS[1]
            local settled = tonumber(redis.call('HGET', k, 'settled') or '0')
            redis.call('HSET', k, 'settled', settled + tonumber(ARGV[1]))
            return 1
            """;

    private static final String RECONCILE_LUA = """
            local k = KEYS[1]
            local limit = tonumber(ARGV[1])
            local settled = tonumber(ARGV[2])
            local held = tonumber(ARGV[3])
            if redis.call('EXISTS', k) == 0 then
              redis.call('HSET', k, 'limit', limit, 'settled', settled, 'held', held)
              return 1
            end
            local cl = tonumber(redis.call('HGET', k, 'limit') or '0')
            local cs = tonumber(redis.call('HGET', k, 'settled') or '0')
            local ch = tonumber(redis.call('HGET', k, 'held') or '0')
            if cl == limit and cs == settled and ch == held then return 0 end
            redis.call('HSET', k, 'limit', limit, 'settled', settled, 'held', held)
            return 2
            """;

    private final StringRedisTemplate redis;
    private final AccountPort accountPort;
    private final LedgerQueryPort ledgerQuery;
    private final ReservationRepositoryPort reservationRepo;

    private final DefaultRedisScript<Long> reserveScript = longScript(RESERVE_LUA);
    private final DefaultRedisScript<Long> releaseScript = longScript(RELEASE_LUA);
    private final DefaultRedisScript<Long> settleScript = longScript(SETTLE_LUA);
    private final DefaultRedisScript<Long> lateSettleScript = longScript(LATE_SETTLE_LUA);
    private final DefaultRedisScript<Long> reconcileScript = longScript(RECONCILE_LUA);

    public RedisReservationGate(StringRedisTemplate redis, AccountPort accountPort, LedgerQueryPort ledgerQuery,
                                ReservationRepositoryPort reservationRepo) {
        this.redis = redis;
        this.accountPort = accountPort;
        this.ledgerQuery = ledgerQuery;
        this.reservationRepo = reservationRepo;
    }

    @Override
    public GateResult tryReserve(String accountId, long estimateMinor) {
        String key = KEY_PREFIX + accountId;
        try {
            Long r = redis.execute(reserveScript, List.of(key), String.valueOf(estimateMinor),
                    String.valueOf(dbLimit(accountId)), String.valueOf(dbSettled(accountId)),
                    String.valueOf(reservationRepo.sumActiveHolds(accountId)));
            return toResult(key, r != null && r == 1);
        } catch (RuntimeException e) {
            throw new DomainExceptions.HoldFailed("Redis 预留门不可用: " + accountId, e);
        }
    }

    @Override
    public void releaseHold(String accountId, long amountMinor) {
        exec(releaseScript, accountId, String.valueOf(amountMinor));
    }

    @Override
    public void applySettlement(String accountId, long estimateMinor, long actualCostMinor) {
        exec(settleScript, accountId, String.valueOf(estimateMinor), String.valueOf(actualCostMinor));
    }

    @Override
    public void applyLateSettlement(String accountId, long actualCostMinor) {
        exec(lateSettleScript, accountId, String.valueOf(actualCostMinor));
    }

    @Override
    public void adjustSettled(String accountId, long deltaMinor) {
        exec(lateSettleScript, accountId, String.valueOf(deltaMinor));
    }

    @Override
    public boolean reconcile(String accountId, long limitMinor, long settledMinor, long heldMinor) {
        String key = KEY_PREFIX + accountId;
        Long r = redis.execute(reconcileScript, List.of(key), String.valueOf(limitMinor),
                String.valueOf(settledMinor), String.valueOf(heldMinor));
        return r != null && r > 0;
    }

    private void exec(DefaultRedisScript<Long> script, String accountId, String... args) {
        try {
            redis.execute(script, List.of(KEY_PREFIX + accountId), args);
        } catch (RuntimeException e) {
            // Redis 更新失败不阻断账本操作：DB 权威 + sweeper 收敛（Q1）
        }
    }

    private GateResult toResult(String key, boolean ok) {
        var values = redis.opsForHash().multiGet(key, List.of("limit", "settled", "held"));
        long limit = parseLong(values, 0);
        long settled = parseLong(values, 1);
        long held = parseLong(values, 2);
        return new GateResult(ok, limit, settled, held);
    }

    private long parseLong(List<Object> values, int i) {
        Object v = values.get(i);
        return v == null ? 0L : Long.parseLong(v.toString());
    }

    private long dbLimit(String accountId) {
        return accountPort.find(accountId).map(AccountPort.AccountSnapshot::quotaLimitMinor).orElse(0L);
    }

    private long dbSettled(String accountId) {
        var sums = ledgerQuery.sumsByType(accountId);
        return sums.getOrDefault(LedgerEntryType.SETTLE, 0L) + sums.getOrDefault(LedgerEntryType.ADJUST, 0L);
    }

    private static DefaultRedisScript<Long> longScript(String lua) {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setScriptText(lua);
        s.setResultType(Long.class);
        return s;
    }
}
