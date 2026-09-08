package io.quotapilot.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.quotapilot.common.DomainExceptions;

/** [API] 全局异常 → 结构化错误（规范 §7/§8 错误码约定）。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainExceptions.QuotaExceeded.class)
    public ResponseEntity<ApiError> quotaExceeded(DomainExceptions.QuotaExceeded e) {
        return build(HttpStatus.PAYMENT_REQUIRED, "QUOTA_EXCEEDED", e.getMessage());
    }

    @ExceptionHandler(DomainExceptions.AccountBlocked.class)
    public ResponseEntity<ApiError> accountBlocked(DomainExceptions.AccountBlocked e) {
        return build(HttpStatus.PAYMENT_REQUIRED, "ACCOUNT_BLOCKED", e.getMessage());
    }

    @ExceptionHandler(DomainExceptions.NoQuotaConfigured.class)
    public ResponseEntity<ApiError> noQuota(DomainExceptions.NoQuotaConfigured e) {
        return build(HttpStatus.FORBIDDEN, "NO_QUOTA_CONFIGURED", e.getMessage());
    }

    @ExceptionHandler(DomainExceptions.PricingUnavailable.class)
    public ResponseEntity<ApiError> pricing(DomainExceptions.PricingUnavailable e) {
        return build(HttpStatus.CONFLICT, "PRICING_UNAVAILABLE", e.getMessage());
    }

    @ExceptionHandler(DomainExceptions.HoldFailed.class)
    public ResponseEntity<ApiError> holdFailed(DomainExceptions.HoldFailed e) {
        log.error("预留基础设施异常", e);
        return build(HttpStatus.SERVICE_UNAVAILABLE, "HOLD_FAILED", e.getMessage());
    }

    @ExceptionHandler(DomainExceptions.RequestStateConflict.class)
    public ResponseEntity<ApiError> conflict(DomainExceptions.RequestStateConflict e) {
        return build(HttpStatus.CONFLICT, "REQUEST_STATE_CONFLICT", e.getMessage());
    }

    @ExceptionHandler(DomainExceptions.IdempotencyConflict.class)
    public ResponseEntity<ApiError> idempotency(DomainExceptions.IdempotencyConflict e) {
        return build(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", e.getMessage());
    }

    @ExceptionHandler(DomainExceptions.RateLimited.class)
    public ResponseEntity<ApiError> rateLimited(DomainExceptions.RateLimited e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(h -> h.add("Retry-After", String.valueOf(e.retryAfterSeconds)))
                .body(new ApiError("RATE_LIMITED", e.getMessage(), TraceIdFilter.currentTraceId()));
    }

    @ExceptionHandler(DomainExceptions.NotFound.class)
    public ResponseEntity<ApiError> notFound(DomainExceptions.NotFound e) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", e.getBindingResult().toString());
    }

    @ExceptionHandler(io.quotapilot.common.Amounts.ArithmeticOverflowException.class)
    public ResponseEntity<ApiError> overflow(io.quotapilot.common.Amounts.ArithmeticOverflowException e) {
        return build(HttpStatus.BAD_REQUEST, "ESTIMATE_OVERFLOW", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> internal(Exception e) {
        log.error("内部错误", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", e.toString());
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, TraceIdFilter.currentTraceId()));
    }
}
