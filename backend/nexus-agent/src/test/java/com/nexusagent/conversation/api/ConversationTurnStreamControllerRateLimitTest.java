package com.nexusagent.conversation.api;

import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.resilience.ResilienceProperties;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 控制器 + 真实限流器 + 异常处理器组合验证：
 * 超限请求在提交线程池之前被拒，映射为 429 + Retry-After。
 */
class ConversationTurnStreamControllerRateLimitTest {

    private static final CurrentActor ACTOR =
            new CurrentActor(
                    7L,
                    3L,
                    "alice",
                    Set.of("MEMBER")
            );

    private final SimpleMeterRegistry registry =
            new SimpleMeterRegistry();

    private final ConversationTurnSseMetrics metrics =
            new ConversationTurnSseMetrics(registry);

    private StreamConversationTurnService service;

    private AsyncTaskExecutor executor;

    private ConversationExceptionHandler handler;

    @BeforeEach
    void setUp() {
        service = mock(StreamConversationTurnService.class);
        executor = mock(AsyncTaskExecutor.class);
        handler = new ConversationExceptionHandler();

        // 同步 executor：submit 时立即执行 worker。
        when(executor.submit(
                org.mockito.ArgumentMatchers.any(Runnable.class)
        )).thenAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);

            task.run();

            return java.util.concurrent.CompletableFuture
                    .completedFuture(null);
        });
    }

    @Test
    void shouldRejectWithRateLimitedBeforeWorkerSubmission() {
        ConversationTurnStreamController controller =
                controllerWithLimits(2);

        controller.stream(
                "901",
                null,
                request("Hello")
        );

        controller.stream(
                "901",
                null,
                request("Hello again")
        );

        ConversationTurnRateLimitedException rejected =
                assertThrows(
                        ConversationTurnRateLimitedException
                                .class,
                        () -> controller.stream(
                                "901",
                                null,
                                request("Third")
                        )
                );

        ResponseEntity<ProblemDetail> response =
                handler.handleTurnRateLimited(rejected);

        assertEquals(
                HttpStatus.TOO_MANY_REQUESTS,
                response.getStatusCode()
        );
        assertEquals(
                "10",
                response.getHeaders()
                        .getFirst("Retry-After")
        );
        assertEquals(
                "CONVERSATION_TURN_RATE_LIMITED",
                response.getBody().getProperties()
                        .get("errorCode")
        );
        assertEquals(
                1.0,
                rateLimitedCount()
        );
    }

    @Test
    void shouldPassIdempotencyKeyToService() {
        ConversationTurnStreamController controller =
                controllerWithLimits(100);

        SseEmitter emitter = controller.stream(
                "901",
                " my-key ",
                request("Hello")
        );

        org.mockito.Mockito.verify(service)
                .stream(
                        org.mockito.ArgumentMatchers.eq("901"),
                        org.mockito.ArgumentMatchers.eq("Hello"),
                        org.mockito.ArgumentMatchers.eq("my-key"),
                        org.mockito.ArgumentMatchers.any(
                                ConversationTurnStreamHandler.class
                        )
                );

        emitter.complete();
    }

    @Test
    void shouldRejectOversizedIdempotencyKey() {
        ConversationTurnStreamController controller =
                controllerWithLimits(100);

        assertThrows(
                IllegalArgumentException.class,
                () -> controller.stream(
                        "901",
                        "k".repeat(129),
                        request("Hello")
                )
        );
    }

    private ConversationTurnStreamController
    controllerWithLimits(int userLimit) {
        CurrentActorProvider provider = mock(
                CurrentActorProvider.class
        );

        when(provider.requireCurrentActor())
                .thenReturn(ACTOR);

        ConversationTurnRateLimiter limiter =
                new ConversationTurnRateLimiter(
                        provider,
                        RateLimiterRegistry.ofDefaults(),
                        properties(userLimit),
                        metrics
                );

        return new ConversationTurnStreamController(
                service,
                executor,
                Duration.ofMinutes(2),
                metrics,
                limiter
        );
    }

    private static StreamConversationTurnRequest request(
            String content
    ) {
        return new StreamConversationTurnRequest(content);
    }

    private static ResilienceProperties properties(
            int userLimit
    ) {
        return new ResilienceProperties(
                new ResilienceProperties.RateLimit(
                        true,
                        100,
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

    private double rateLimitedCount() {
        return registry
                .find(ConversationTurnSseMetrics
                        .RATE_LIMITED_COUNTER)
                .counter()
                .count();
    }
}
