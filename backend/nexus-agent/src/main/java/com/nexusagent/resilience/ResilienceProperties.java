package com.nexusagent.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

/**
 * 会话轮次与模型调用的弹性配置。
 *
 * <p>三个子域：
 * <ul>
 *     <li>{@code rate-limit}：tenant/user 两级会话轮次限流；</li>
 *     <li>{@code circuit-breaker}：模型供应商熔断；</li>
 *     <li>{@code model-retry}：仅在尚未收到首个模型事件时的
 *         安全重试。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "nexus.resilience")
public record ResilienceProperties(
        @DefaultValue RateLimit rateLimit,
        @DefaultValue CircuitBreaker circuitBreaker,
        @DefaultValue ModelRetry modelRetry
) {

    public ResilienceProperties {
        Objects.requireNonNull(
                rateLimit,
                "rateLimit must not be null"
        );
        Objects.requireNonNull(
                circuitBreaker,
                "circuitBreaker must not be null"
        );
        Objects.requireNonNull(
                modelRetry,
                "modelRetry must not be null"
        );
    }

    /**
     * tenant/user 两级限流。限流器按名称惰性创建：
     * {@code conversation-turn:tenant:{id}} 与
     * {@code conversation-turn:tenant:{id}:user:{uid}}。
     *
     * <p>默认值：tenant 60 次/10s（360 次/分钟）、
     * user 6 次/10s（36 次/分钟）——足以支撑演示与冒烟脚本，
     * 同时抑制失控客户端。
     */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,

            @DefaultValue("60") int tenantLimitForPeriod,

            @DefaultValue("10s") Duration tenantRefreshPeriod,

            @DefaultValue("6") int userLimitForPeriod,

            @DefaultValue("10s") Duration userRefreshPeriod
    ) {

        public RateLimit {
            if (tenantLimitForPeriod < 1) {
                throw new IllegalArgumentException(
                        "tenantLimitForPeriod must be at least 1"
                );
            }

            if (userLimitForPeriod < 1) {
                throw new IllegalArgumentException(
                        "userLimitForPeriod must be at least 1"
                );
            }

            requirePositive(
                    tenantRefreshPeriod,
                    "tenantRefreshPeriod"
            );
            requirePositive(
                    userRefreshPeriod,
                    "userRefreshPeriod"
            );
        }
    }

    /**
     * 模型供应商熔断（每个 provider 一台）。
     */
    public record CircuitBreaker(
            @DefaultValue("20") int slidingWindowSize,

            @DefaultValue("20") int minimumNumberOfCalls,

            @DefaultValue("50") float failureRateThreshold,

            @DefaultValue("30s") Duration waitDurationInOpenState,

            @DefaultValue("3")
            int permittedNumberOfCallsInHalfOpenState
    ) {

        public CircuitBreaker {
            if (slidingWindowSize < 2) {
                throw new IllegalArgumentException(
                        "slidingWindowSize must be at least 2"
                );
            }

            if (minimumNumberOfCalls < 2) {
                throw new IllegalArgumentException(
                        "minimumNumberOfCalls must be at least 2"
                );
            }

            if (failureRateThreshold <= 0f
                    || failureRateThreshold >= 100f) {
                throw new IllegalArgumentException(
                        "failureRateThreshold must be in (0, 100)"
                );
            }

            requirePositive(
                    waitDurationInOpenState,
                    "waitDurationInOpenState"
            );

            if (permittedNumberOfCallsInHalfOpenState < 1) {
                throw new IllegalArgumentException(
                        "permittedNumberOfCallsInHalfOpenState "
                                + "must be at least 1"
                );
            }
        }
    }

    /**
     * 模型调用安全重试：只有当异常可重试且<b>尚未向客户端
     * 转发任何模型事件</b>时才重试。
     */
    public record ModelRetry(
            @DefaultValue("3") int maxAttempts,

            @DefaultValue("200ms") Duration initialBackoff,

            @DefaultValue("4") int backoffMultiplier
    ) {

        public ModelRetry {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException(
                        "maxAttempts must be at least 1"
                );
            }

            requirePositive(initialBackoff, "initialBackoff");

            if (backoffMultiplier < 1) {
                throw new IllegalArgumentException(
                        "backoffMultiplier must be at least 1"
                );
            }
        }
    }

    private static void requirePositive(
            Duration value,
            String field
    ) {
        if (value == null
                || value.isNegative()
                || value.isZero()) {
            throw new IllegalArgumentException(
                    field + " must be positive"
            );
        }
    }
}
