package com.nexusagent.tool.internal;

final class ToolExecutionRegistrationScopeException
        extends RuntimeException {

    ToolExecutionRegistrationScopeException() {
        super("Tool execution registration scope not found");
    }
}