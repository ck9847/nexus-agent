package com.nexusagent.tool.api;

public final class ToolExecutionNotFoundException
        extends RuntimeException {

    public ToolExecutionNotFoundException() {
        super("Tool execution not found");
    }
}
