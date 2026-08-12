package com.nexusagent.agent.internal.persistence;

import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.agent.domain.AgentStatus;

public record ActiveAgentRuntimeRow(
        long id,
        long tenantId,
        String code,
        String systemPrompt,
        AgentModelProvider modelProvider,
        String modelName,
        String modelConfigJson,
        AgentStatus status
) {
}