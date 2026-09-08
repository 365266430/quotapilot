package io.quotapilot.common;

import java.time.Instant;

/**
 * [M11/P8] 时间源唯一原则：系统内所有时间一律取自可注入时钟，禁止直接调用 Instant.now()。
 */
public interface TimeService {

    Instant now();
}
