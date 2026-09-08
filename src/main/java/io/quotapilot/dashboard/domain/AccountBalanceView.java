package io.quotapilot.dashboard.domain;

/**
 * [M9] 账户实时预算视图：limit / settled / held / exposure / available（P3 权威口径，DB 账本重算）。
 */
public record AccountBalanceView(String accountId, String scopeType, String scopeId, long limitMinor,
                                 long settledMinor, long heldMinor, long exposureOpenMinor, long exposureOpenCount,
                                 long availableMinor, String currency) {}
