package io.quotapilot.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.quotapilot.gateway.domain.GatewayOrchestrator;
import io.quotapilot.gateway.domain.GatewayRequest;
import io.quotapilot.gateway.domain.GatewayResult;
import io.quotapilot.gateway.domain.RequestQueryService;
import io.quotapilot.settlement.domain.ReleaseReason;
import io.quotapilot.settlement.domain.SettlementResult;
import io.quotapilot.settlement.domain.SettlementService;

/** [M3/M4/M6] 请求 API：预留并发出（POST）、状态（GET）、取消释放（DELETE）。 */
@RestController
public class RequestController {

    private final GatewayOrchestrator orchestrator;
    private final RequestQueryService query;
    private final SettlementService settlementService;

    public RequestController(GatewayOrchestrator orchestrator, RequestQueryService query,
                             SettlementService settlementService) {
        this.orchestrator = orchestrator;
        this.query = query;
        this.settlementService = settlementService;
    }

    public record CreateReq(String requestId, String userId, String teamId, String taskId, String model,
                            Long declaredEstimatedUnits, String payload, Boolean reserveOnly, Long ttlSeconds,
                            String supplier) {}

    @PostMapping("/v1/requests")
    public GatewayResult create(@RequestBody CreateReq req) {
        return orchestrator.execute(new GatewayRequest(req.requestId(), req.userId(), req.teamId(), req.taskId(),
                req.model(), req.declaredEstimatedUnits(), req.payload(),
                Boolean.TRUE.equals(req.reserveOnly()), req.ttlSeconds() == null ? 0 : req.ttlSeconds(), null,
                req.supplier()));
    }

    @GetMapping("/v1/requests/{requestId}")
    public RequestQueryService.RequestView status(@PathVariable String requestId) {
        return query.status(requestId);
    }

    /** 取消：释放预留。V1 同步调用模型下取消请求按「确定未产生外部费用」处理；在途请求由超时 sweeper 收敛。 */
    @DeleteMapping("/v1/requests/{requestId}")
    public SettlementResult cancel(@PathVariable String requestId) {
        return settlementService.release(requestId, ReleaseReason.CANCELLED, false);
    }
}
