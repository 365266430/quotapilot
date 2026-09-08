package io.quotapilot.ledger.domain;

/**
 * [M0] 账户范围类型。在规范最低要求 user/team/model/task 之上，
 * 补充 USER_MODEL/TEAM_MODEL 以承载「用户+模型」「团队+模型」组合规则（规范 M1 解析优先级要求）。
 */
public enum ScopeType {
    USER, TEAM, MODEL, TASK, USER_MODEL, TEAM_MODEL
}
