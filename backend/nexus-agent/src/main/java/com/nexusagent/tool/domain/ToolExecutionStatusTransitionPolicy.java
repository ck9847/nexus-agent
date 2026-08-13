package com.nexusagent.tool.domain;

import java.util.Objects;

public final class ToolExecutionStatusTransitionPolicy {

    private ToolExecutionStatusTransitionPolicy() {
    }

    public static boolean isAllowed(
            ToolExecutionStatus currentStatus,
            ToolExecutionStatus targetStatus
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
            case PENDING ->
                    targetStatus == ToolExecutionStatus.RUNNING
                            || targetStatus
                            == ToolExecutionStatus.WAITING_APPROVAL
                            || targetStatus
                            == ToolExecutionStatus.FAILED
                            || targetStatus
                            == ToolExecutionStatus.CANCELLED;

            case WAITING_APPROVAL ->
                    targetStatus == ToolExecutionStatus.RUNNING
                            || targetStatus
                            == ToolExecutionStatus.CANCELLED;

            case RUNNING ->
                    targetStatus == ToolExecutionStatus.SUCCEEDED
                            || targetStatus
                            == ToolExecutionStatus.FAILED
                            || targetStatus
                            == ToolExecutionStatus.CANCELLED;

            case SUCCEEDED, FAILED, CANCELLED -> false;
        };
    }

    public static void requireAllowed(
            ToolExecutionStatus currentStatus,
            ToolExecutionStatus targetStatus
    ) {
        if (!isAllowed(currentStatus, targetStatus)) {
            throw new
                    InvalidToolExecutionStatusTransitionException(
                    currentStatus,
                    targetStatus
            );
        }
    }
}