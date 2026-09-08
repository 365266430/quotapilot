package io.quotapilot.ledger.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * [M0] 账本只读查询端口：回放审计与余额重算（P3 权威口径：对外展示以 DB 账本重算为准）。
 */
public interface LedgerQueryPort {

    /** 按 类型 汇总金额：SETTLE/RELEASE/ADJUST 累计；ADJUST 带符号。 */
    Map<LedgerEntryType, Long> sumsByType(String accountId);

    /** 全量流水（回放用），按创建时间升序。 */
    List<LedgerEntry> allEntries(String accountId);

    /** 账本全量条数（对账一致性校验用）。 */
    long countEntries(String accountId);

    /** 近期有账本活动的账户（sweeper 做 Redis 对账的扫描范围）。 */
    List<String> recentActiveAccountIds(Instant since);

    Optional<SettlementRecord> settlementRecord(String requestId);

    /** 单请求的净入账 = ΣSETTLE + ΣADJUST（对账差额计算用）。 */
    long netChargedByRequest(String requestId);
}
