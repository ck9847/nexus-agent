package com.nexusagent.agent.api;

import com.nexusagent.agent.domain.AgentModelConfig;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.agent.domain.AgentStatus;

import java.time.Instant;

public record AgentDetailResponse(
        String agentId,
        String code,
        String name,
        String description,
        String systemPrompt,
        AgentModelProvider modelProvider,
        String modelName,
        AgentModelConfig modelConfig,
        AgentStatus status,
        String createdByUserId,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}