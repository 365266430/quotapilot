package io.quotapilot.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.quotapilot.reserve.domain.ReservationGatePort;

/**
 * [测试支撑] 内存版预留门：语义与 Redis Lua 脚本一致（available = limit − settled − held）。
 */
public class FakeGate implements ReservationGatePort {

    public record Balances(long limit, long settled, long held) {}

    public final Map<String, Balances> accounts = new ConcurrentHashMap<>();

    public void init(String accountId, long limit) {
        accounts.put(accountId, new Balances(limit, 0, 0));
    }

    private Balances of(String accountId) {
        return accounts.computeIfAbsent(accountId, k -> new Balances(0, 0, 0));
    }

    @Override
    public synchronized GateResult tryReserve(String accountId, long estimateMinor) {
        Balances b = of(accountId);
        long available = b.limit() - b.settled() - b.held();
        if (available >= estimateMinor) {
            accounts.put(accountId, new Balances(b.limit(), b.settled(), b.held() + estimateMinor));
            return new GateResult(true, b.limit(), b.settled(), b.held() + estimateMinor);
        }
        return new GateResult(false, b.limit(), b.settled(), b.held());
    }

    @Override
    public synchronized void releaseHold(String accountId, long amountMinor) {
        Balances b = of(accountId);
        accounts.put(accountId, new Balances(b.limit(), b.settled(), Math.max(0, b.held() - amountMinor)));
    }

    @Override
    public synchronized void applySettlement(String accountId, long estimateMinor, long actualCostMinor) {
        Balances b = of(accountId);
        accounts.put(accountId, new Balances(b.limit(), b.settled() + actualCostMinor,
                Math.max(0, b.held() - estimateMinor)));
    }

    @Override
    public synchronized void applyLateSettlement(String accountId, long actualCostMinor) {
        Balances b = of(accountId);
        accounts.put(accountId, new Balances(b.limit(), b.settled() + actualCostMinor, b.held()));
    }

    @Override
    public synchronized void adjustSettled(String accountId, long deltaMinor) {
        applyLateSettlement(accountId, deltaMinor);
    }

    @Override
    public synchronized boolean reconcile(String accountId, long limitMinor, long settledMinor, long heldMinor) {
        Balances b = of(accountId);
        boolean drift = b.limit() != limitMinor || b.settled() != settledMinor || b.held() != heldMinor;
        accounts.put(accountId, new Balances(limitMinor, settledMinor, heldMinor));
        return drift;
    }
}
