package com.nexusagent.conversation.internal;

import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.conversation.api.ConversationTurnStreamEvent;
import com.nexusagent.conversation.api.ConversationTurnStreamHandler;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelStreamConsumerException;
import com.nexusagent.resilience.ResilienceProperties;
import com.nexusagent.testing.ThrowingMeterRegistry;
import io.micrometer.core.instrument.MockClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeModelRetryExecutorTest {

    private static final AgentModelProvider PROVIDER =
            AgentModelProvider.OPENAI;

    private final ThrowingMeterRegistry registry =
            new ThrowingMeterRegistry(new MockClock());

    private final ConversationTurnMetrics metrics =
            new ConversationTurnMetrics(registry);

    @Test
    void shouldRetryRetryableFailureBeforeFirstForwardedEvent() {
        SafeModelRetryExecutor executor =
                executor(3, 1L);

        AtomicInteger attempts = new AtomicInteger();

        String result = executor.execute(
                PROVIDER,
                noOpHandler(),
                guard -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw retryable();
                    }

                    return "done";
                }
        );

        assertEquals("done", result);
        assertEquals(3, attempts.get());
        assertEquals(
                2,
                retryCount("attempted")
        );
        assertEquals(
                1,
                retryCount("succeeded")
        );
    }

    @Test
    void shouldNeverRetryAfterFirstForwardedEvent() {
        SafeModelRetryExecutor executor =
                executor(3, 1L);

        AtomicInteger attempts = new AtomicInteger();

        ChatModelException failure = assertThrows(
                ChatModelException.class,
                () -> executor.execute(
                        PROVIDER,
                        recordingHandler(),
                        guard -> {
                            attempts.incrementAndGet();

                            // 先转发一个事件，再抛出可重试异常：
                            // 客户端已经收到内容，重试必然重复。
                            guard.onEvent(textDelta());

                            throw retryable();
                        }
                )
        );

        assertEquals(
                ChatModelErrorCategory.RATE_LIMIT,
                failure.category()
        );
        assertEquals(1, attempts.get());
        assertEquals(
                1,
                retryCount("blocked_by_first_event")
        );
        assertEquals(
                0,
                retryCount("attempted")
        );
    }

    @Test
    void shouldNotRetryNonRetryableFailure() {
        SafeModelRetryExecutor executor =
                executor(3, 1L);

        AtomicInteger attempts = new AtomicInteger();

        assertThrows(
                ChatModelException.class,
                () -> executor.execute(
                        PROVIDER,
                        noOpHandler(),
                        guard -> {
                            attempts.incrementAndGet();

                            throw new ChatModelException(
                                    ChatModelErrorCategory
                                            .AUTHENTICATION,
                                    "auth"
                            );
                        }
                )
        );

        assertEquals(1, attempts.get());
        assertEquals(0, retryCount("attempted"));
    }

    @Test
    void shouldSurfaceOriginalFailureAfterExhaustingAttempts() {
        SafeModelRetryExecutor executor =
                executor(3, 1L);

        AtomicInteger attempts = new AtomicInteger();
        ChatModelException original = retryable();

        ChatModelException failure = assertThrows(
                ChatModelException.class,
                () -> executor.execute(
                        PROVIDER,
                        noOpHandler(),
                        guard -> {
                            attempts.incrementAndGet();

                            throw original;
                        }
                )
        );

        assertSame(original, failure);
        assertEquals(3, attempts.get());
        assertEquals(
                2,
                retryCount("attempted")
        );
        assertEquals(
                1,
                retryCount("exhausted")
        );
    }

    @Test
    void shouldNeverRetryConsumerFailure() {
        SafeModelRetryExecutor executor =
                executor(3, 1L);

        AtomicInteger attempts = new AtomicInteger();

        ChatModelStreamConsumerException consumer =
                new ChatModelStreamConsumerException(
                        "client gone",
                        new IllegalStateException("sse")
                );

        assertThrows(
                ChatModelStreamConsumerException.class,
                () -> executor.execute(
                        PROVIDER,
                        noOpHandler(),
                        guard -> {
                            attempts.incrementAndGet();

                            throw consumer;
                        }
                )
        );

        assertEquals(1, attempts.get());
        assertEquals(0, retryCount("attempted"));
    }

    @Test
    void shouldApplyExponentialBackoffBetweenAttempts() {
        // 确定性退避：attempt 0/1/2 -> 10/20/30ms，
        // 三次重试合计至少 60ms。
        SafeModelRetryExecutor executor =
                new SafeModelRetryExecutor(
                        properties(4),
                        attempt -> 10L * (attempt + 1),
                        metrics
                );

        long start = System.nanoTime();

        assertThrows(
                ChatModelException.class,
                () -> executor.execute(
                        PROVIDER,
                        noOpHandler(),
                        guard -> {
                            throw retryable();
                        }
                )
        );

        long elapsedMillis =
                (System.nanoTime() - start) / 1_000_000;

        assertTrue(
                elapsedMillis >= 55,
                "backoff should delay retries, elapsed="
                        + elapsedMillis + "ms"
        );
    }

    @Test
    void shouldNotRetryWhenMaxAttemptsIsOne() {
        SafeModelRetryExecutor executor =
                executor(1, 1L);

        AtomicInteger attempts = new AtomicInteger();

        assertThrows(
                ChatModelException.class,
                () -> executor.execute(
                        PROVIDER,
                        noOpHandler(),
                        guard -> {
                            attempts.incrementAndGet();

                            throw retryable();
                        }
                )
        );

        assertEquals(1, attempts.get());
    }

    private SafeModelRetryExecutor executor(
            int maxAttempts,
            long backoffMillis
    ) {
        return new SafeModelRetryExecutor(
                properties(maxAttempts),
                attempt -> backoffMillis,
                metrics
        );
    }

    private ResilienceProperties properties(int maxAttempts) {
        return new ResilienceProperties(
                new ResilienceProperties.RateLimit(
                        true,
                        60,
                        Duration.ofSeconds(10),
                        6,
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
                        maxAttempts,
                        Duration.ofMillis(1),
                        4
                )
        );
    }

    private static ChatModelException retryable() {
        return new ChatModelException(
                ChatModelErrorCategory.RATE_LIMIT,
                "rate limited"
        );
    }

    private static ConversationTurnStreamEvent.TextDelta
    textDelta() {
        return new ConversationTurnStreamEvent.TextDelta(
                "Hi"
        );
    }

    private static ConversationTurnStreamHandler
    noOpHandler() {
        return event -> {
        };
    }

    private static ConversationTurnStreamHandler
    recordingHandler() {
        List<ConversationTurnStreamEvent> events =
                new ArrayList<>();

        return events::add;
    }

    private double retryCount(String outcome) {
        var counter = registry
                .find(ConversationTurnMetrics.MODEL_RETRY_METRIC)
                .tag("provider", "OPENAI")
                .tag("outcome", outcome)
                .counter();

        return counter == null ? 0.0 : counter.count();
    }
}
