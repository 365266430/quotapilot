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
}
