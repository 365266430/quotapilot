package io.quotapilot.alert.domain;

import java.time.Instant;

/**
 * [M9] 告警事件。severity: INFO/WARN/P0。
 */
public record Alert(AlertType type, String severity, String accountId, String requestId, String message, Instant at) {

    public static Alert of(AlertType type, String severity, String accountId, String requestId, String message, Instant at) {
        return new Alert(type, severity, accountId, requestId, message, at);
    }
}
