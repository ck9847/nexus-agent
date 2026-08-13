package com.nexusagent.tool.internal;

import java.time.Instant;
import java.util.Objects;

public record ClaimedCreateTicketToolExecution(
        AgentToolExecutionContext context,
        CreateTicketToolArguments arguments,
        Instant startedAt,
        ExecuteCreateTicketToolResult replayResult
) {

    public ClaimedCreateTicketToolExecution {
        Objects.requireNonNull(
                context,
                "context must not be null"
        );
        Objects.requireNonNull(
                startedAt,
                "startedAt must not be null"
        );

        boolean hasArguments = arguments != null;
        boolean hasReplay = replayResult != null;

        if (hasArguments == hasReplay) {
            throw new IllegalArgumentException(
                    "Claim must be either fresh "
                            + "or replay"
            );
        }
    }

    public static ClaimedCreateTicketToolExecution fresh(
            AgentToolExecutionContext context,
            CreateTicketToolArguments arguments,
            Instant startedAt
    ) {
        return new ClaimedCreateTicketToolExecution(
                context,
                arguments,
                startedAt,
                null
        );
    }

    public static ClaimedCreateTicketToolExecution replay(
            AgentToolExecutionContext context,
            Instant startedAt,
            ExecuteCreateTicketToolResult replayResult
    ) {
        return new ClaimedCreateTicketToolExecution(
                context,
                null,
                startedAt,
                replayResult
        );
    }
}
