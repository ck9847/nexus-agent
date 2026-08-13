package com.nexusagent.tool.internal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Service
public class DefaultExecuteCreateTicketToolService {

    private static final String INVALID_TOOL_INPUT =
            "INVALID_TOOL_INPUT";

    private static final String SAFE_INVALID_INPUT_MESSAGE =
            "Create ticket tool input is invalid";

    private static final String CREATE_TICKET_TOOL_FAILED =
            "CREATE_TICKET_TOOL_FAILED";

    private static final String SAFE_EXECUTION_FAILED_MESSAGE =
            "Create ticket tool execution failed";

    private final CreateTicketToolExecutionTransactions
            transactions;
    private final Clock clock;

    public DefaultExecuteCreateTicketToolService(
            CreateTicketToolExecutionTransactions
                    transactions,
            Clock clock
    ) {
        this.transactions = Objects.requireNonNull(
                transactions
        );
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ExecuteCreateTicketToolResult execute(
            AgentToolExecutionContext context
    ) {
        Objects.requireNonNull(
                context,
                "context must not be null"
        );

        ClaimedCreateTicketToolExecution claim;

        try {
            claim = transactions.claim(context);
        } catch (IllegalArgumentException inputFailure) {
            finalizeFailure(context, inputFailure);

            throw inputFailure;
        }

        if (claim.replayResult() != null) {
            return claim.replayResult();
        }

        try {
            return transactions.succeed(claim);
        } catch (RuntimeException executionFailure) {
            finalizeFailure(context, executionFailure);

            throw executionFailure;
        }
    }

    private void finalizeFailure(
            AgentToolExecutionContext context,
            RuntimeException executionFailure
    ) {
        CreateTicketToolFailure failure =
                classify(executionFailure);

        try {
            transactions.fail(context, failure);
        } catch (RuntimeException finalizationFailure) {
            if (finalizationFailure != executionFailure) {
                finalizationFailure.addSuppressed(
                        executionFailure
                );
            }

            throw finalizationFailure;
        }
    }

    private CreateTicketToolFailure classify(
            RuntimeException failure
    ) {
        Instant failedAt = clock.instant()
                .truncatedTo(ChronoUnit.MILLIS);

        if (failure instanceof IllegalArgumentException) {
            return new CreateTicketToolFailure(
                    INVALID_TOOL_INPUT,
                    SAFE_INVALID_INPUT_MESSAGE,
                    failedAt
            );
        }

        return new CreateTicketToolFailure(
                CREATE_TICKET_TOOL_FAILED,
                SAFE_EXECUTION_FAILED_MESSAGE,
                failedAt
        );
    }
}
