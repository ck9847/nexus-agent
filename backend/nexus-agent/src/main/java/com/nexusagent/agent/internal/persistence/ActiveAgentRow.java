package com.nexusagent.agent.internal.persistence;

import com.nexusagent.agent.domain.AgentStatus;

public record ActiveAgentRow(
        long id,
        long tenantId,
        String code,
        AgentStatus status
) {
}