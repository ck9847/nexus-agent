package com.nexusagent.tool.internal;

/**
 * Executes the create_ticket tool and compensates failed executions.
 *
 * <p>{@link #failPending} exists so that after an execution has been
 * registered and persisted as PENDING, a caller that fails before
 * invoking {@link #execute} (for example when completing the tool
 * call message) can still finalize the orphan PENDING record as
 * FAILED instead of leaving it behind forever.
 */
public interface CreateTicketToolExecutionService {

    ExecuteCreateTicketToolResult execute(
            AgentToolExecutionContext context
    );

    void failPending(
            AgentToolExecutionContext context,
            RuntimeException failure
    );
}
