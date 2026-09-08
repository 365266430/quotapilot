package io.quotapilot.reserve.domain;

/**
 * [M3/P4] 预留原子门端口：Redis Lua 实现「检查+扣减」一步完成，杜绝先查后扣竞态。
 * 账本口径：available = limit − settled − held。DB 为权威，Redis 为热路径加速（Q1）。
 */
public interface ReservationGatePort {

    GateResult tryReserve(String accountId, long estimateMinor);

    /** 全额退回 held（失败/取消/超时到期）。 */
    void releaseHold(String accountId, long amountMinor);

    /** 正常结算：held -= estimate; settled += actual（若 actual > estimate 由 DB 口径修正，见 reconcile）。 */
    void applySettlement(String accountId, long estimateMinor, long actualCostMinor);

    /** 迟到结算（预留已释放）：仅 settled += actual。 */
    void applyLateSettlement(String accountId, long actualCostMinor);

    /** 对账修正：settled += delta（可正可负）。 */
    void adjustSettled(String accountId, long deltaMinor);

    /**
     * sweeper 对账：以 DB 权威口径覆写 Redis 账本（limit/settled/activeHeld），
     * 处理「Redis 丢失→重建」「Redis 漂移→纠正」（Q1/P7）。返回 true 表示发生了重建/纠正。
     */
    boolean reconcile(String accountId, long limitMinor, long settledMinor, long heldMinor);

    record GateResult(boolean ok, long limitMinor, long settledMinor, long heldMinor) {

        public long availableMinor() {
            return limitMinor - settledMinor - heldMinor;
        }
    }
}
