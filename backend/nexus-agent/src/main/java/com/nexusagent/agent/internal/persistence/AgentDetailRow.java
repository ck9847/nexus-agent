package com.nexusagent.agent.internal.persistence;

import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.agent.domain.AgentStatus;

import java.time.Instant;

public record AgentDetailRow(
        long id,
        long tenantId,
        String code,
        String name,
        String description,
        String systemPrompt,
        AgentModelProvider modelProvider,
        String modelName,
        String modelConfigJson,
        AgentStatus status,
        long createdByUserId,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}