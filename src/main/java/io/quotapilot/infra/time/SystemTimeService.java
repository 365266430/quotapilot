package io.quotapilot.infra.time;

import java.time.Instant;

import org.springframework.stereotype.Component;

import io.quotapilot.common.TimeService;

/** [M11/P8] 系统时钟实现（可替换为单调时间服务；测试注入固定时钟）。 */
@Component
public class SystemTimeService implements TimeService {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
