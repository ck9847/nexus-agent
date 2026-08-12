package com.nexusagent.agent.api;

import com.nexusagent.agent.domain.AgentModelConfig;
import com.nexusagent.agent.domain.AgentModelProvider;

import java.util.Objects;

public record ActiveAgentRuntime(
        long agentId,
        long tenantId,
        String code,
        String systemPrompt,
        AgentModelProvider modelProvider,
        String modelName,
        AgentModelConfig modelConfig
) {

    public ActiveAgentRuntime {
        if (agentId <= 0) {
            throw new IllegalArgumentException(
                    "agentId must be positive"
            );
        }

        if (tenantId <= 0) {
            throw new IllegalArgumentException(
                    "tenantId must be positive"
            );
        }

        requireText(code, "code");
        requireText(systemPrompt, "systemPrompt");
        requireText(modelName, "modelName");

        Objects.requireNonNull(
                modelProvider,
                "modelProvider must not be null"
        );
    }

    private static void requireText(
            String value,
            String field
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }
    }
}