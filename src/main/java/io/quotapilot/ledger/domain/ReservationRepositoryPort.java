package io.quotapilot.ledger.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * [M0] Reservation 仓储端口。
 */
public interface ReservationRepositoryPort {

    void save(Reservation reservation);

    void update(Reservation reservation);

    Optional<Reservation> findByRequestId(String requestId);

    /** 已到期仍未结算的预留（sweeper 扫描 → 转 Exposure，不允许静默消失，P7）。 */
    List<Reservation> findExpiredBefore(Instant at);

    List<Reservation> findByAccount(String accountId, int limit);

    /** Σ(当前 RESERVED 预留额) —— Redis 对账与面板的在途口径。 */
    long sumActiveHolds(String accountId);

    /** 已结算的请求 id（对账「本地有供应商无」扫描用）。 */
    List<String> findSettledRequestIds(int limit);
}
