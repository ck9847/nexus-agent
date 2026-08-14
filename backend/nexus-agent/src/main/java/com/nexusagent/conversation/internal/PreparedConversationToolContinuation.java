package com.nexusagent.conversation.internal;

import com.nexusagent.agent.api.ActiveAgentRuntime;
import com.nexusagent.model.api.ChatModelRequest;
import com.nexusagent.model.api.ChatModelToolCall;

import java.time.Instant;
import java.util.Objects;

/**
 * The prepared second-round continuation after a create_ticket tool
 * execution: the tool result message and the new ASSISTANT placeholder
 * have been written, and the follow-up model request has been built.
 */
public record PreparedConversationToolContinuation(
        long tenantId,
        long userId,
        long conversationId,
        ActiveAgentRuntime agent,
        long toolExecutionId,
        long resultMessageId,
        long resultMessageSequenceNo,
        long assistantMessageId,
        long assistantSequenceNo,
        int conversationVersion,
        Instant preparedAt,
        ChatModelToolCall toolCall,
        ChatModelRequest modelRequest
) implements AssistantMessageCompletionTarget {

    public PreparedConversationToolContinuation {
        if (tenantId <= 0
                || userId <= 0
                || conversationId <= 0
                || toolExecutionId <= 0
                || resultMessageId <= 0
                || assistantMessageId <= 0) {
            throw new IllegalArgumentException(
                    "Continuation IDs must be positive"
            );
        }

        if (resultMessageId == assistantMessageId) {
            throw new IllegalArgumentException(
                    "Continuation message IDs must be distinct"
            );
        }

        if (resultMessageSequenceNo <= 0
                || assistantSequenceNo
                != resultMessageSequenceNo + 1) {
            throw new IllegalArgumentException(
                    "Continuation message sequences "
                            + "must be consecutive"
            );
        }

        if (conversationVersion < 1) {
            throw new IllegalArgumentException(
                    "conversationVersion must be positive"
            );
        }

        Objects.requireNonNull(agent, "agent must not be null");
        Objects.requireNonNull(
                preparedAt,
                "preparedAt must not be null"
        );
        Objects.requireNonNull(
                toolCall,
                "toolCall must not be null"
        );
        Objects.requireNonNull(
                modelRequest,
                "modelRequest must not be null"
        );

        if (agent.tenantId() != tenantId) {
            throw new IllegalArgumentException(
                    "Agent tenant must match "
                            + "continuation tenant"
            );
        }

        if (!modelRequest.tools().isEmpty()) {
            throw new IllegalArgumentException(
                    "Continuation model request "
                            + "must not declare tools"
            );
        }
    }
}
