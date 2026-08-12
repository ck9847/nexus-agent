package com.nexusagent.model.api;

import com.nexusagent.agent.domain.AgentModelProvider;

public interface ChatModelGatewayResolver {

    ChatModelGateway requireGateway(
            AgentModelProvider provider
    );
}