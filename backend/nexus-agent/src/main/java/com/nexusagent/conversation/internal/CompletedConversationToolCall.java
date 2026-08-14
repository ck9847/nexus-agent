package com.nexusagent.conversation.internal;

import com.nexusagent.model.api.ChatModelToolCall;
import com.nexusagent.model.api.ChatTokenUsage;

import java.time.Instant;
import java.util.Objects;

public record CompletedConversationToolCall(
        long tenantId,
        long userId,
        long conversationId,
        long agentId,
        long assistantMessageId,
        long assistantSequenceNo,
        ChatModelToolCall toolCall,
        long toolExecutionId,
        String modelName,
        ChatTokenUsage usage,
        Instant createdAt,
        Instant completedAt
) {

    public CompletedConversationToolCall {
        if (tenantId <= 0
                || userId <= 0
                || conversationId <= 0
                || agentId <= 0
                || assistantMessageId <= 0
                || assistantSequenceNo <= 0
                || toolExecutionId <= 0) {
            throw new IllegalArgumentException(
                    "Completed tool call IDs and "
                            + "sequence must be positive"
            );
        }

        if (assistantMessageId == toolExecutionId) {
            throw new IllegalArgumentException(
                    "Assistant message and tool "
                            + "execution IDs must differ"
            );
        }

        Objects.requireNonNull(
                toolCall,
                "toolCall must not be null"
        );

        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException(
                    "modelName must not be blank"
            );
        }

        Objects.requireNonNull(
                usage,
                "usage must not be null"
        );
        Objects.requireNonNull(
                createdAt,
                "createdAt must not be null"
        );
        Objects.requireNonNull(
                completedAt,
                "completedAt must not be null"
        );

        if (completedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "completedAt must not be before createdAt"
            );
        }
    }
}
