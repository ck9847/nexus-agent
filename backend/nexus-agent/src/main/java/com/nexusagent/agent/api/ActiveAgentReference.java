package com.nexusagent.agent.api;

public record ActiveAgentReference(
        long agentId,
        long tenantId,
        String code
) {

    public ActiveAgentReference {
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

        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException(
                    "code must not be blank"
            );
        }
    }
}