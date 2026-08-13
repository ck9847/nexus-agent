package com.nexusagent.tool.api;

public final class
ToolExecutionIdempotencyConflictException
        extends RuntimeException {

    public ToolExecutionIdempotencyConflictException() {
        super("Tool execution idempotency conflict");
    }
}