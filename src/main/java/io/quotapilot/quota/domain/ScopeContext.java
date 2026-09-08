package io.quotapilot.quota.domain;

/**
 * [M1] 额度规则解析上下文（上游身份体系不在本项目范围内，见规范 §11）。
 */
public record ScopeContext(String userId, String teamId, String model, String taskId) {

    public static ScopeContext of(String userId, String teamId, String model, String taskId) {
        return new ScopeContext(userId, teamId, model, taskId);
    }
}
