package com.nexusagent.conversation.internal;

import com.nexusagent.agent.api.ActiveAgentRuntime;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable, value-typed snapshot of an
 * {@link AssistantMessageCompletionTarget}.
 *
 * <p>Captures the final ASSISTANT placeholder coordinates after a tool
 * transaction has committed, so a subsequent failure while assembling
 * the continuation request can still finalize that placeholder as
 * FAILED without relying on the original turn object.
 */
public record AssistantMessageCompletionTargetSnapshot(
        long tenantId,
        long userId,
        long conversationId,
        ActiveAgentRuntime agent,
        long assistantMessageId,
        long assistantSequenceNo,
        int conversationVersion,
        Instant preparedAt
) implements AssistantMessageCompletionTarget {

    public AssistantMessageCompletionTargetSnapshot {
        if (tenantId <= 0
                || userId <= 0
                || conversationId <= 0
                || assistantMessageId <= 0
                || assistantSequenceNo <= 0
                || conversationVersion <= 0) {
            throw new IllegalArgumentException(
                    "Completion target values must be positive"
            );
        }

        Objects.requireNonNull(agent);
        Objects.requireNonNull(preparedAt);

        if (agent.tenantId() != tenantId) {
            throw new IllegalArgumentException(
                    "Agent tenant must match target tenant"
            );
        }
    }
}
