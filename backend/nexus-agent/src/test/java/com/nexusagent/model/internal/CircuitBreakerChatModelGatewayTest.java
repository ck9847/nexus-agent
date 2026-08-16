package com.nexusagent.model.internal;

import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelGateway;
import com.nexusagent.model.api.ChatModelMessage;
import com.nexusagent.model.api.ChatModelOptions;
import com.nexusagent.model.api.ChatModelRequest;
import com.nexusagent.model.api.ChatModelRole;
import com.nexusagent.model.api.ChatModelStreamConsumerException;
import com.nexusagent.model.api.ChatModelStreamHandler;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CircuitBreakerChatModelGatewayTest {

    private static final ChatModelRequest REQUEST =
            new ChatModelRequest(
                    "gpt-5-mini",
                    "sys",
                    new ChatModelOptions(null, null, null),
                    List.of(new ChatModelMessage(
                            ChatModelRole.USER,
                            "hi",
                            List.of(),
                            null
                    )),
                    List.of()
            );

    private static ChatModelStreamHandler handler() {
        return event -> {
        };
    }

    @Test
    void shouldFastFailWithCircuitOpenCategoryWhenOpen() {
        ChatModelGateway delegate = mock(
                ChatModelGateway.class
        );

        CircuitBreaker circuitBreaker = registry()
                .circuitBreaker("model:OPENAI");

        CircuitBreakerChatModelGateway gateway =
                new CircuitBreakerChatModelGateway(
                        delegate,
                        circuitBreaker
                );

        circuitBreaker.transitionToForcedOpenState();

        ChatModelException failure = assertThrows(
                ChatModelException.class,
                () -> gateway.stream(REQUEST, handler())
        );

        assertEquals(
                ChatModelErrorCategory.CIRCUIT_OPEN,
                failure.category()
        );
        assertTrue(
                failure.getMessage()
                        .contains("circuit breaker is open")
        );

        // OPEN 状态下绝不触碰真实供应商。
        verify(delegate, times(0))
                .stream(any(), any());
    }

    @Test
    void shouldOpenAfterConfiguredFailureRate() {
        ChatModelGateway delegate = mock(
                ChatModelGateway.class
        );

        doThrow(new ChatModelException(
                ChatModelErrorCategory.PROVIDER_UNAVAILABLE,
                "provider down"
        )).when(delegate).stream(any(), any());

        CircuitBreakerRegistry registry = registry(
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(4)
                        .minimumNumberOfCalls(4)
                        .failureRateThreshold(50f)
                        .waitDurationInOpenState(
                                Duration.ofSeconds(30)
                        )
                        .permittedNumberOfCallsInHalfOpenState(1)
                        .build()
        );

        CircuitBreaker circuitBreaker =
                registry.circuitBreaker("model:OPENAI");

        CircuitBreakerChatModelGateway gateway =
                new CircuitBreakerChatModelGateway(
                        delegate,
                        circuitBreaker
                );

        // minimumNumberOfCalls=4：第 4 次真实失败满足评估条件，
        // 失败率 100% >= 50%，熔断打开。
        for (int i = 0; i < 4; i++) {
            assertThrows(
                    ChatModelException.class,
                    () -> gateway.stream(REQUEST, handler())
            );
        }

        assertEquals(
                CircuitBreaker.State.OPEN,
                circuitBreaker.getState()
        );

        // OPEN 后的调用快速失败，绝不触碰真实供应商。
        ChatModelException fifth = assertThrows(
                ChatModelException.class,
                () -> gateway.stream(REQUEST, handler())
        );

        assertEquals(
                ChatModelErrorCategory.CIRCUIT_OPEN,
                fifth.category()
        );
        verify(delegate, times(4)).stream(any(), any());
    }

    @Test
    void shouldNotCountClientDisconnectTowardOpening() {
        ChatModelGateway delegate = mock(
                ChatModelGateway.class
        );

        doThrow(new ChatModelStreamConsumerException(
                "client gone",
                new IllegalStateException("sse closed")
        )).when(delegate).stream(any(), any());

        CircuitBreakerRegistry registry = registry(
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(4)
                        .minimumNumberOfCalls(4)
                        .failureRateThreshold(50f)
                        // 生产同款谓词：消费侧异常不计失败。
                        .ignoreException(
                                throwable ->
                                        throwable
                                                instanceof
                                                ChatModelStreamConsumerException
                        )
                        .build()
        );

        CircuitBreaker circuitBreaker =
                registry.circuitBreaker("model:OPENAI");

        CircuitBreakerChatModelGateway gateway =
                new CircuitBreakerChatModelGateway(
                        delegate,
                        circuitBreaker
                );

        for (int i = 0; i < 10; i++) {
            assertThrows(
                    ChatModelStreamConsumerException.class,
                    () -> gateway.stream(REQUEST, handler())
            );
        }

        assertEquals(
                CircuitBreaker.State.CLOSED,
                circuitBreaker.getState()
        );
    }

    @Test
    void shouldRecoverThroughHalfOpenAfterWait() {
        ChatModelGateway delegate = mock(
                ChatModelGateway.class
        );

        CircuitBreakerRegistry registry = registry(
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(2)
                        .minimumNumberOfCalls(2)
                        .failureRateThreshold(50f)
                        .waitDurationInOpenState(
                                Duration.ofMillis(10)
                        )
                        .permittedNumberOfCallsInHalfOpenState(1)
                        .build()
        );

        CircuitBreaker circuitBreaker =
                registry.circuitBreaker("model:OPENAI");

        CircuitBreakerChatModelGateway gateway =
                new CircuitBreakerChatModelGateway(
                        delegate,
                        circuitBreaker
                );

        doThrow(new ChatModelException(
                ChatModelErrorCategory.CONNECTION,
                "conn"
        )).when(delegate).stream(any(), any());

        for (int i = 0; i < 2; i++) {
            assertThrows(
                    ChatModelException.class,
                    () -> gateway.stream(REQUEST, handler())
            );
        }

        assertEquals(
                CircuitBreaker.State.OPEN,
                circuitBreaker.getState()
        );

        // 等待窗口结束后放行试探：试探成功。
        doNothing().when(delegate).stream(any(), any());

        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(e);
        }

        // OPEN -> HALF_OPEN 的迁移发生在下一次许可请求时：
        // 该调用就是唯一的试探，成功后回到 CLOSED。
        gateway.stream(REQUEST, handler());

        assertEquals(
                CircuitBreaker.State.CLOSED,
                circuitBreaker.getState()
        );
        verify(delegate, times(3)).stream(any(), any());
    }

    @Test
    void shouldPassThroughProviderAndExceptions() {
        ChatModelGateway delegate = mock(
                ChatModelGateway.class
        );

        when(delegate.provider())
                .thenReturn(AgentModelProvider.OPENAI);

        CircuitBreakerChatModelGateway gateway =
                new CircuitBreakerChatModelGateway(
                        delegate,
                        registry().circuitBreaker("model:OPENAI")
                );

        assertEquals(
                AgentModelProvider.OPENAI,
                gateway.provider()
        );

        ChatModelException original = new ChatModelException(
                ChatModelErrorCategory.AUTHENTICATION,
                "auth"
        );

        doThrow(original).when(delegate).stream(any(), any());

        ChatModelException propagated = assertThrows(
                ChatModelException.class,
                () -> gateway.stream(REQUEST, handler())
        );

        assertSame(original, propagated);
    }

    /**
     * 与生产装配（ResilienceConfiguration）一致的谓词语义：
     * 消费侧异常与 STREAM_INTERRUPTED 被忽略，
     * 其他 ChatModelException 计入失败。
     */
    private static CircuitBreakerRegistry registry() {
        return registry(
                CircuitBreakerConfig.custom()
                        .ignoreException(
                                throwable ->
                                        throwable
                                                instanceof
                                                ChatModelStreamConsumerException
                                                || (throwable
                                                instanceof
                                                ChatModelException failure
                                                && failure.category()
                                                == ChatModelErrorCategory
                                                .STREAM_INTERRUPTED)
                        )
                        .recordException(
                                throwable ->
                                        throwable
                                                instanceof
                                                ChatModelException
                        )
                        .build()
        );
    }

    private static CircuitBreakerRegistry registry(
            CircuitBreakerConfig config
    ) {
        return CircuitBreakerRegistry.of(config);
    }
}
