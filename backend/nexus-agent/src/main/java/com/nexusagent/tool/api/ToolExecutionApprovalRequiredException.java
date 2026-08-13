package com.nexusagent.tool.api;

public final class ToolExecutionApprovalRequiredException
        extends RuntimeException {

    public ToolExecutionApprovalRequiredException() {
        super("Tool execution requires approval");
    }
}
