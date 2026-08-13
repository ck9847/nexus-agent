package com.nexusagent.tool.api;

public final class ToolExecutionTerminalStateException
        extends RuntimeException {

    public ToolExecutionTerminalStateException() {
        super("Tool execution has reached "
                + "a terminal state");
    }
}
