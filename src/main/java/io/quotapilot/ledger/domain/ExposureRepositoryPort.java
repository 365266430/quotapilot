package io.quotapilot.ledger.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * [M0] 未决敞口仓储端口。
 */
public interface ExposureRepositoryPort {

    void save(ExposureRecord exposure);

    void update(ExposureRecord exposure);

    Optional<ExposureRecord> findByRequestId(String requestId);

    Optional<ExposureRecord> findById(String exposureId);

    /** 宽限期已过仍未收敛的敞口（sweeper → CLOSED_WITH_ADJUSTMENT）。 */
    List<ExposureRecord> findPendingPastGrace(Instant now);

    /** 未决敞口数量（积压告警用）。 */
    long countOpen(String accountId);

    List<ExposureRecord> findOpenByAccount(String accountId);
}
