package io.quotapilot.api;

/**
 * [API] 结构化错误：错误码 + 人类可读信息 + traceId（规范 §7）。
 */
public record ApiError(String code, String message, String traceId) {}
