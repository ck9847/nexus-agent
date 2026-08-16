package com.nexusagent.conversation.api;

import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.resilience.ResilienceProperties;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationTurnRateLimiterTest {

    private static final CurrentActor ACTOR =
            new CurrentActor(
                    7L,
                    3L,
                    "alice",
                    Set.of("MEMBER")
            );

    @Test
    void shouldAllowRequestsUnderBothLimits() {
        ConversationTurnRateLimiter limiter =
                limiter(properties(true, 10, 5), ACTOR);

        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(
                    limiter::checkPermission
            );
        }
    }

    @Test
    void shouldRejectWhenUserLimitExceeded() {
        ConversationTurnRateLimiter limiter =
                limiter(properties(true, 10, 2), ACTOR);

        limiter.checkPermission();
        limiter.checkPermission();

        ConversationTurnRateLimitedException rejected =
                assertThrows(
                        ConversationTurnRateLimitedException
                                .class,
                        limiter::checkPermission
                );

        // 窗口 10s >= 1s 下限：Retry-After 即刷新窗口。
        assertEquals(
                Duration.ofSeconds(10),
                rejected.retryAfter()
        );
        assertEquals(
                1.0,
                rateLimitedCount()
        );
    }

    @Test
    void shouldEnforceTenantLimitAcrossUsersOfSameTenant() {
        // tenant 上限 2：同租户第二个用户共享租户总闸。
        CurrentActor bob = new CurrentActor(
                8L,
                3L,
                "bob",
                Set.of("MEMBER")
        );

        // 共享同一注册表：租户闸的状态跨实例生效。
        RateLimiterRegistry shared =
                RateLimiterRegistry.ofDefaults();

        ConversationTurnRateLimiter limiter =
                new ConversationTurnRateLimiter(
                        () -> ACTOR,
                        shared,
                        properties(true, 2, 100),
                        metrics
                );

        limiter.checkPermission();

        ConversationTurnRateLimiter bobLimiter =
                new ConversationTurnRateLimiter(
                        () -> bob,
                        shared,
                        properties(true, 2, 100),
                        metrics
                );

        bobLimiter.checkPermission();

        assertThrows(
                ConversationTurnRateLimitedException.class,
                bobLimiter::checkPermission
        );
    }

    @Test
    void shouldNotConsumeUserPermitWhenTenantRejected() {
        // 同一租户的两个用户共享一个注册表（租户闸状态全局）。
        RateLimiterRegistry shared =
                RateLimiterRegistry.ofDefaults();

        ConversationTurnRateLimiter limiter =
                new ConversationTurnRateLimiter(
                        () -> ACTOR,
                        shared,
                        properties(true, 1, 1),
                        metrics
                );

        limiter.checkPermission();

        assertThrows(
                ConversationTurnRateLimitedException.class,
                limiter::checkPermission
        );

        // 租户闸已耗尽：同租户的另一个用户立即被拒。
        CurrentActorProvider bobProvider = mock(
                CurrentActorProvider.class
        );

        when(bobProvider.requireCurrentActor())
                .thenReturn(new CurrentActor(
                        9L,
                        3L,
                        "bob",
                        Set.of("MEMBER")
                ));

        ConversationTurnRateLimiter bobLimiter =
                new ConversationTurnRateLimiter(
                        bobProvider,
                        shared,
                        properties(true, 1, 1),
                        metrics
                );

        assertThrows(
                ConversationTurnRateLimitedException.class,
                bobLimiter::checkPermission
        );
    }

    @Test
    void shouldBypassWhenDisabled() {
        ConversationTurnRateLimiter limiter =
                limiter(properties(false, 1, 1), ACTOR);

        for (int i = 0; i < 10; i++) {
            assertDoesNotThrow(
                    limiter::checkPermission
            );
        }

        assertEquals(0.0, rateLimitedCount());
    }

    @Test
    void shouldFailOpenWhenRegistryFails() {
        RateLimiterRegistry broken =
                mock(RateLimiterRegistry.class);

        when(broken.rateLimiter(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(
                        io.github.resilience4j
                                .ratelimiter
                                .RateLimiterConfig.class
                )
        )).thenThrow(
                new IllegalStateException("registry boom")
        );

        ConversationTurnRateLimiter limiter =
                new ConversationTurnRateLimiter(
                        () -> ACTOR,
                        broken,
                        properties(true, 1, 1),
                        metrics
                );

        // 限流器自身故障必须放行，绝不放大为服务不可用。
        assertDoesNotThrow(limiter::checkPermission);
    }

    private final SimpleMeterRegistry meterRegistry =
            new SimpleMeterRegistry();

    private final ConversationTurnSseMetrics metrics =
            new ConversationTurnSseMetrics(meterRegistry);

    private ConversationTurnRateLimiter limiter(
            ResilienceProperties properties,
            CurrentActor actor
    ) {
        return limiterFor(properties, actor);
    }

    private ConversationTurnRateLimiter limiterFor(
            ResilienceProperties properties,
            CurrentActor actor
    ) {
        CurrentActorProvider provider = mock(
                CurrentActorProvider.class
        );

        when(provider.requireCurrentActor())
                .thenReturn(actor);

        return new ConversationTurnRateLimiter(
                provider,
                RateLimiterRegistry.ofDefaults(),
                properties,
                metrics
        );
    }

    private double rateLimitedCount() {
        return meterRegistry
                .find(
                        ConversationTurnSseMetrics
                                .RATE_LIMITED_COUNTER
                )
                .counter()
                .count();
    }

    private static ResilienceProperties properties(
            boolean enabled,
            int tenantLimit,
            int userLimit
    ) {
        return new ResilienceProperties(
                new ResilienceProperties.RateLimit(
                        enabled,
                        tenantLimit,
                        Duration.ofSeconds(10),
                        userLimit,
                        Duration.ofSeconds(10)
                ),
                new ResilienceProperties.CircuitBreaker(
                        20,
                        20,
                        50f,
                        Duration.ofSeconds(30),
                        3
                ),
                new ResilienceProperties.ModelRetry(
                        3,
                        Duration.ofMillis(1),
                        4
                )
        );
    }
}
