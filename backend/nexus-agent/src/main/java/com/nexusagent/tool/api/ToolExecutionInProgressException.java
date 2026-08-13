package com.nexusagent.tool.api;

public final class ToolExecutionInProgressException
        extends RuntimeException {

    public ToolExecutionInProgressException() {
        super("Tool execution is already running");
    }
}
