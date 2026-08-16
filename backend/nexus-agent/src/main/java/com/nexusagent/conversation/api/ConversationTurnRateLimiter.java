package com.nexusagent.conversation.api;

import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.resilience.ResilienceProperties;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * 会话轮次的 tenant/user 两级限流。
 *
 * <p>必须在<b>提交线程池之前</b>的请求线程上调用（依赖当前
 * JWT {@code SecurityContext} 解析 actor）。任何一级拒绝都抛出
 * {@link ConversationTurnRateLimitedException}，由
 * {@code ConversationExceptionHandler} 映射为 429 +
 * {@code Retry-After}。
 *
 * <p>限流器实例按名称惰性创建并驻留注册表：
 * {@code conversation-turn:tenant:{id}}（租户总闸）与
 * {@code conversation-turn:tenant:{id}:user:{uid}}（单用户）。
 * 实例数量上界为 2 × 租户用户数，指标注册表会自动为惰性实例
 * 绑定 {@code resilience4j_ratelimiter_*}。
 */
@Component
public final class ConversationTurnRateLimiter {

    private static final String NAME_PREFIX =
            "conversation-turn:tenant:";

    private final CurrentActorProvider currentActorProvider;

    private final RateLimiterRegistry registry;

    private final ResilienceProperties properties;

    private final ConversationTurnSseMetrics metrics;

    public ConversationTurnRateLimiter(
            CurrentActorProvider currentActorProvider,
            RateLimiterRegistry registry,
            ResilienceProperties properties,
            ConversationTurnSseMetrics metrics
    ) {
        this.currentActorProvider = Objects.requireNonNull(
                currentActorProvider,
                "currentActorProvider must not be null"
        );
        this.registry = Objects.requireNonNull(
                registry,
                "registry must not be null"
        );
        this.properties = Objects.requireNonNull(
                properties,
                "properties must not be null"
        );
        this.metrics = Objects.requireNonNull(
                metrics,
                "metrics must not be null"
        );
    }

    /**
     * 消耗一个 tenant 许可与一个 user 许可。
     *
     * <p>非阻塞：任一级无可用许可立即拒绝。先检查租户级、
     * 再检查用户级——租户级拒绝时不消耗用户级许可。
     */
    public void checkPermission() {
        if (!properties.rateLimit().enabled()) {
            return;
        }

        CurrentActor actor =
                currentActorProvider.requireCurrentActor();

        RateLimiter tenantLimiter = limiterOrNull(
                NAME_PREFIX + actor.tenantId(),
                tenantConfig()
        );

        if (tenantLimiter != null
                && !tryAcquire(tenantLimiter)) {
            metrics.countRateLimited();
            throw new ConversationTurnRateLimitedException(
                    retryAfter(tenantLimiter)
            );
        }

        RateLimiter userLimiter = limiterOrNull(
                NAME_PREFIX + actor.tenantId()
                        + ":user:" + actor.userId(),
                userConfig()
        );

        if (userLimiter != null
                && !tryAcquire(userLimiter)) {
            metrics.countRateLimited();
            throw new ConversationTurnRateLimitedException(
                    retryAfter(userLimiter)
            );
        }
    }

    /**
     * 获取（必要时创建）限流器实例。注册表自身故障返回
     * null：限流组件不可用时放行，绝不放大为服务不可用。
     */
    private RateLimiter limiterOrNull(
            String name,
            RateLimiterConfig config
    ) {
        try {
            return registry.rateLimiter(name, config);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private boolean tryAcquire(RateLimiter limiter) {
        try {
            // timeoutDuration 为 0：无许可时立即返回 false，
            // 绝不阻塞请求线程。
            return limiter.acquirePermission();
        } catch (RuntimeException failure) {
            // 限流器自身故障必须放行：可用性优先于过载保护。
            return true;
        }
    }

    private Duration retryAfter(RateLimiter limiter) {
        RateLimiterConfig config =
                limiter.getRateLimiterConfig();

        // 至少 1 秒，避免 Retry-After: 0 引发的立刻重放风暴。
        Duration refresh =
                config.getLimitRefreshPeriod();

        return refresh.compareTo(Duration.ofSeconds(1)) < 0
                ? Duration.ofSeconds(1)
                : refresh;
    }

    private RateLimiterConfig tenantConfig() {
        return RateLimiterConfig.custom()
                .limitForPeriod(
                        properties.rateLimit()
                                .tenantLimitForPeriod()
                )
                .limitRefreshPeriod(
                        properties.rateLimit()
                                .tenantRefreshPeriod()
                )
                .timeoutDuration(Duration.ZERO)
                .build();
    }

    private RateLimiterConfig userConfig() {
        return RateLimiterConfig.custom()
                .limitForPeriod(
                        properties.rateLimit()
                                .userLimitForPeriod()
                )
                .limitRefreshPeriod(
                        properties.rateLimit()
                                .userRefreshPeriod()
                )
                .timeoutDuration(Duration.ZERO)
                .build();
    }
}
