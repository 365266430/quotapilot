package io.quotapilot.metering.domain;

import java.util.List;
import java.util.Optional;

/**
 * [M8] 用量事件存储端口：幂等入库 + 明细分页查询（面板与对账的数据底座）。
 */
public interface UsageEventPort {

    /** @return true=新插入；false=重复事件已安全丢弃（P6）。 */
    boolean record(UsageEvent event);

    Optional<UsageEvent> find(String requestId, UsageSource source, long seq);

    List<UsageEvent> findByRequestId(String requestId);

    List<UsageEvent> pageByAccount(String accountId, int page, int size);

    long countByAccount(String accountId);

    /** [M7 重建] 账户在 since 之后的请求数（REQUESTS 维度滑动窗口重建）。 */
    long countByAccountSince(String accountId, java.time.Instant since);

    /** [M7 重建] 账户在 since 之后的用量合计（TOKENS 维度令牌桶重建）。 */
    long sumQuantityByAccountSince(String accountId, java.time.Instant since);
}
