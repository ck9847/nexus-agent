package com.nexusagent.agent.domain;

import java.util.Objects;

public final class AgentStatusTransitionPolicy {

    private AgentStatusTransitionPolicy() {
    }

    public static boolean isAllowed(
            AgentStatus currentStatus,
            AgentStatus targetStatus
    ) {
        Objects.requireNonNull(
                currentStatus,
                "currentStatus must not be null"
        );

        Objects.requireNonNull(
                targetStatus,
                "targetStatus must not be null"
        );

        return switch (currentStatus) {
            case DRAFT ->
                    targetStatus == AgentStatus.ACTIVE;

            case ACTIVE ->
                    targetStatus == AgentStatus.DISABLED;

            case DISABLED ->
                    targetStatus == AgentStatus.ACTIVE;
        };
    }

    public static void requireAllowed(
            AgentStatus currentStatus,
            AgentStatus targetStatus
    ) {
        if (!isAllowed(
                currentStatus,
                targetStatus
        )) {
            throw new InvalidAgentStatusTransitionException(
                    currentStatus,
                    targetStatus
            );
        }
    }
}