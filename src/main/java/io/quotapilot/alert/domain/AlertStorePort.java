package io.quotapilot.alert.domain;

import java.util.List;

/**
 * [M9] 告警存储端口（面板查询 + 抑制窗口判断的数据源）。
 */
public interface AlertStorePort {

    void save(Alert alert);

    List<Alert> recent(int limit);

    /** 同(type,accountId) 在冷却窗口内的最近一条告警，用于抑制重复告警。 */
    long countSince(AlertType type, String accountId, java.time.Instant since);
}
