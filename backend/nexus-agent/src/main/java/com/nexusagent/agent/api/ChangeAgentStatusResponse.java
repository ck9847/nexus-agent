package com.nexusagent.agent.api;

import com.nexusagent.agent.domain.AgentStatus;

import java.time.Instant;

public record ChangeAgentStatusResponse(
        String agentId,
        String code,
        AgentStatus previousStatus,
        AgentStatus currentStatus,
        int version,
        Instant updatedAt
) {
}