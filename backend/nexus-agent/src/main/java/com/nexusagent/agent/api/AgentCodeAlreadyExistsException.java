package com.nexusagent.agent.api;

import java.util.Objects;

public final class AgentCodeAlreadyExistsException
        extends RuntimeException {

    private final String agentCode;

    public AgentCodeAlreadyExistsException(
            String agentCode
    ) {
        super(
                "Agent code already exists: "
                        + agentCode
        );

        this.agentCode = Objects.requireNonNull(
                agentCode,
                "agentCode must not be null"
        );
    }

    public AgentCodeAlreadyExistsException(
            String agentCode,
            Throwable cause
    ) {
        super(
                "Agent code already exists: "
                        + agentCode,
                cause
        );

        this.agentCode = Objects.requireNonNull(
                agentCode,
                "agentCode must not be null"
        );
    }

    public String getAgentCode() {
        return agentCode;
    }
}