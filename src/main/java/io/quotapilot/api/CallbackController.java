package io.quotapilot.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.quotapilot.metering.domain.CallbackService;

/** [M8] 供应商用量回调：POST /v1/callbacks/{supplier}，幂等（重复回调 → 409 DUPLICATE_EVENT）。 */
@RestController
public class CallbackController {

    private final CallbackService callbackService;

    public CallbackController(CallbackService callbackService) {
        this.callbackService = callbackService;
    }

    public record CallbackReq(String requestId, String supplierRequestId, long units, Long seq) {}

    @PostMapping("/v1/callbacks/{supplier}")
    public ResponseEntity<CallbackResultView> callback(@PathVariable("supplier") String supplier,
                                                       @RequestBody CallbackReq req) {
        long seq = req.seq() == null ? 0L : req.seq();
        CallbackService.CallbackResult r = callbackService.ingest(supplier,
                new CallbackService.CallbackPayload(req.requestId(), req.supplierRequestId(), req.units(), seq, null));
        if (r.status() == CallbackService.Status.DUPLICATE) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new CallbackResultView("DUPLICATE_EVENT", r.requestId(), r.settlementStatus()));
        }
        return ResponseEntity.ok(new CallbackResultView(r.status().name(), r.requestId(), r.settlementStatus()));
    }

    public record CallbackResultView(String status, String requestId, String settlementStatus) {}
}
