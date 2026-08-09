package com.nexusagent.agent.domain;

import java.util.Objects;

public final class InvalidAgentStatusTransitionException
        extends RuntimeException {

    private final AgentStatus currentStatus;
    private final AgentStatus targetStatus;

    public InvalidAgentStatusTransitionException(
            AgentStatus currentStatus,
            AgentStatus targetStatus
    ) {
        super(
                "Cannot transition agent from "
                        + currentStatus
                        + " to "
                        + targetStatus
        );

        this.currentStatus = Objects.requireNonNull(
                currentStatus,
                "currentStatus must not be null"
        );

        this.targetStatus = Objects.requireNonNull(
                targetStatus,
                "targetStatus must not be null"
        );
    }

    public AgentStatus currentStatus() {
        return currentStatus;
    }

    public AgentStatus targetStatus() {
        return targetStatus;
    }
}