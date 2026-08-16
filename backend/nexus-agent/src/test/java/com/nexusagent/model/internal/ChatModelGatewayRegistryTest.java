package com.nexusagent.model.internal;

import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelGateway;
import com.nexusagent.model.api.ChatModelMessage;
import com.nexusagent.model.api.ChatModelOptions;
import com.nexusagent.model.api.ChatModelRequest;
import com.nexusagent.model.api.ChatModelRole;
import com.nexusagent.model.api.ChatModelStreamHandler;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatModelGatewayRegistryTest {

    @Test
    void shouldResolveConfiguredGateway() {
        ChatModelGateway openAi =
                gateway(AgentModelProvider.OPENAI);

        ChatModelGatewayRegistry registry =
                new ChatModelGatewayRegistry(
                        List.of(openAi),
                        CircuitBreakerRegistry.ofDefaults()
                );

        // 注册表返回熔断装饰后的网关：不再是原始实例，
        // 但 provider 透传、stream 调用委托到配置的网关。
        ChatModelGateway resolved =
                registry.requireGateway(
                        AgentModelProvider.OPENAI
                );

        assertNotSame(openAi, resolved);
        assertEquals(
                AgentModelProvider.OPENAI,
                resolved.provider()
        );

        ChatModelRequest request =
                new ChatModelRequest(
                        "gpt-5-mini",
                        "sys",
                        new ChatModelOptions(
                                null, null, null
                        ),
                        List.of(new ChatModelMessage(
                                ChatModelRole.USER,
                                "hi",
                                List.of(),
                                null
                        )),
                        List.of()
                );
        ChatModelStreamHandler handler = event -> {
        };

        resolved.stream(request, handler);

        verify(openAi).stream(request, handler);
    }

    @Test
    void shouldThrowChatModelExceptionWhenNoGatewayConfigured() {
        ChatModelGatewayRegistry registry =
                new ChatModelGatewayRegistry(
                        List.of(),
                        CircuitBreakerRegistry.ofDefaults()
                );

        assertThrows(
                ChatModelException.class,
                () -> registry.requireGateway(
                        AgentModelProvider.OPENAI
                )
        );
    }

    @Test
    void shouldClassifyMissingGatewayAsProviderUnavailable() {
        ChatModelGatewayRegistry registry =
                new ChatModelGatewayRegistry(
                        List.of(),
                        CircuitBreakerRegistry.ofDefaults()
                );

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> registry.requireGateway(
                        AgentModelProvider.OPENAI
                )
        );

        assertEquals(
                ChatModelErrorCategory.PROVIDER_UNAVAILABLE,
                exception.category()
        );
    }

    @Test
    void shouldMakeMissingGatewayErrorRetryable() {
        ChatModelGatewayRegistry registry =
                new ChatModelGatewayRegistry(
                        List.of(),
                        CircuitBreakerRegistry.ofDefaults()
                );

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> registry.requireGateway(
                        AgentModelProvider.OPENAI
                )
        );

        assertTrue(exception.retryable());
    }

    @Test
    void shouldFailWhenTwoGatewaysDeclareSameProvider() {
        ChatModelGateway first =
                gateway(AgentModelProvider.OPENAI);
        ChatModelGateway second =
                gateway(AgentModelProvider.OPENAI);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new ChatModelGatewayRegistry(
                        List.of(first, second),
                        CircuitBreakerRegistry.ofDefaults()
                )
        );

        assertEquals(
                "Multiple chat model gateways "
                        + "are configured for OPENAI",
                exception.getMessage()
        );
    }

    @Test
    void shouldFailWhenGatewayProviderIsNull() {
        ChatModelGateway gateway = mock(ChatModelGateway.class);

        assertThrows(
                NullPointerException.class,
                () -> new ChatModelGatewayRegistry(
                        List.of(gateway),
                        CircuitBreakerRegistry.ofDefaults()
                )
        );
    }

    @Test
    void shouldFailWhenListContainsNull() {
        ChatModelGateway openAi =
                gateway(AgentModelProvider.OPENAI);

        assertThrows(
                NullPointerException.class,
                () -> new ChatModelGatewayRegistry(
                        List.of(openAi, null),
                        CircuitBreakerRegistry.ofDefaults()
                )
        );
    }

    @Test
    void shouldRejectNullProviderOnRequireGateway() {
        ChatModelGatewayRegistry registry =
                new ChatModelGatewayRegistry(
                        List.of(),
                        CircuitBreakerRegistry.ofDefaults()
                );

        assertThrows(
                NullPointerException.class,
                () -> registry.requireGateway(null)
        );
    }

    private static ChatModelGateway gateway(
            AgentModelProvider provider
    ) {
        ChatModelGateway gateway =
                mock(ChatModelGateway.class);

        when(gateway.provider()).thenReturn(provider);

        return gateway;
    }
}
