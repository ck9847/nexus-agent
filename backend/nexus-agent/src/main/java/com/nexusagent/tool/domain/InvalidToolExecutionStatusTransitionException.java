package com.nexusagent.tool.domain;

import java.util.Objects;

public final class
InvalidToolExecutionStatusTransitionException
        extends RuntimeException {

    private final ToolExecutionStatus currentStatus;
    private final ToolExecutionStatus targetStatus;

    public InvalidToolExecutionStatusTransitionException(
            ToolExecutionStatus currentStatus,
            ToolExecutionStatus targetStatus
    ) {
        super(message(currentStatus, targetStatus));

        this.currentStatus = Objects.requireNonNull(
                currentStatus,
                "currentStatus must not be null"
        );
        this.targetStatus = Objects.requireNonNull(
                targetStatus,
                "targetStatus must not be null"
        );
    }

    public ToolExecutionStatus currentStatus() {
        return currentStatus;
    }

    public ToolExecutionStatus targetStatus() {
        return targetStatus;
    }

    private static String message(
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

        return "Cannot transition tool execution from "
                + currentStatus
                + " to "
                + targetStatus;
    }
}