package com.nexusagent.agent.api;

import com.nexusagent.agent.domain.AgentStatus;

public record CreateAgentResponse(
        String agentId,
        String code,
        AgentStatus status,
        int version
) {
}