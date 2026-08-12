package com.nexusagent.model.internal;

import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelGateway;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class ChatModelGatewayRegistry {

    private final Map<AgentModelProvider, ChatModelGateway>
            gateways;

    public ChatModelGatewayRegistry(
            List<ChatModelGateway> gateways
    ) {
        Objects.requireNonNull(
                gateways,
                "gateways must not be null"
        );

        EnumMap<AgentModelProvider, ChatModelGateway>
                indexed =
                new EnumMap<>(AgentModelProvider.class);

        for (ChatModelGateway gateway : gateways) {
            Objects.requireNonNull(
                    gateway,
                    "gateways must not contain null"
            );

            AgentModelProvider provider =
                    Objects.requireNonNull(
                            gateway.provider(),
                            "gateway provider must not be null"
                    );

            ChatModelGateway previous =
                    indexed.putIfAbsent(provider, gateway);

            if (previous != null) {
                throw new IllegalStateException(
                        "Multiple chat model gateways "
                                + "are configured for "
                                + provider.name()
                );
            }
        }

        this.gateways = Map.copyOf(indexed);
    }

    public ChatModelGateway requireGateway(
            AgentModelProvider provider
    ) {
        Objects.requireNonNull(
                provider,
                "provider must not be null"
        );

        ChatModelGateway gateway = gateways.get(provider);

        if (gateway == null) {
            throw new ChatModelException(
                    ChatModelErrorCategory.PROVIDER_UNAVAILABLE,
                    "Chat model provider is not configured"
            );
        }

        return gateway;
    }
}