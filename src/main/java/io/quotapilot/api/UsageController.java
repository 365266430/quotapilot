package io.quotapilot.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.quotapilot.metering.domain.UsageEvent;
import io.quotapilot.metering.domain.UsageEventPort;

/** [M8] 使用明细 API：GET /v1/usages?accountId=...（分页，面板与对账数据底座）。 */
@RestController
public class UsageController {

    private final UsageEventPort usageEvents;

    public UsageController(UsageEventPort usageEvents) {
        this.usageEvents = usageEvents;
    }

    @GetMapping("/v1/usages")
    public Map<String, Object> usages(@RequestParam("accountId") String accountId,
                                      @RequestParam(name = "page", defaultValue = "0") int page,
                                      @RequestParam(name = "size", defaultValue = "50") int size) {
        List<UsageEvent> events = usageEvents.pageByAccount(accountId, page, size);
        return Map.of("accountId", accountId, "page", page, "size", size,
                "total", usageEvents.countByAccount(accountId), "events", events);
    }
}
