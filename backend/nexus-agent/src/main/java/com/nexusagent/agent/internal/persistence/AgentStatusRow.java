package com.nexusagent.agent.internal.persistence;

import com.nexusagent.agent.domain.AgentStatus;

import java.time.Instant;

public record AgentStatusRow(
        long id,
        long tenantId,
        String code,
        AgentStatus status,
        int version,
        Instant updatedAt
) {
}