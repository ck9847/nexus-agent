package com.nexusagent.model.internal;

import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatModelGatewayRegistryTest {

    @Test
    void shouldResolveConfiguredGateway() {
        ChatModelGateway openAi =
                gateway(AgentModelProvider.OPENAI);

        ChatModelGatewayRegistry registry =
                new ChatModelGatewayRegistry(
                        List.of(openAi)
                );

        assertSame(
                openAi,
                registry.requireGateway(
                        AgentModelProvider.OPENAI
                )
        );
    }

    @Test
    void shouldThrowChatModelExceptionWhenNoGatewayConfigured() {
        ChatModelGatewayRegistry registry =
                new ChatModelGatewayRegistry(List.of());

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
                new ChatModelGatewayRegistry(List.of());

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
                new ChatModelGatewayRegistry(List.of());

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
                        List.of(first, second)
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
                        List.of(gateway)
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
                        List.of(openAi, null)
                )
        );
    }

    @Test
    void shouldRejectNullProviderOnRequireGateway() {
        ChatModelGatewayRegistry registry =
                new ChatModelGatewayRegistry(List.of());

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
