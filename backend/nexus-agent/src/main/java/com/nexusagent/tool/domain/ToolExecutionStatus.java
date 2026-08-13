package com.nexusagent.tool.domain;

public enum ToolExecutionStatus {

    PENDING,
    RUNNING,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED
                || this == FAILED
                || this == CANCELLED;
    }
}