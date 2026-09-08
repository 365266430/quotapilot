package io.quotapilot.quota.domain;

import java.util.Optional;

/**
 * [M1] 额度策略解析器。
 */
public interface QuotaPolicyResolver {

    /**
     * 解析优先级（从高到低，规范 M1）：
     * task > user+model > team+model > user > team > model > 默认。
     * 未命中且无系统默认时返回 empty（调用方拒绝并提示「未配置」）。
     */
    Optional<EffectiveQuota> resolve(ScopeContext context);
}
