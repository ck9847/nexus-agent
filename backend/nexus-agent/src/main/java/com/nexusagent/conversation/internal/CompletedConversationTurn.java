package com.nexusagent.conversation.internal;

import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatTokenUsage;

import java.time.Instant;
import java.util.Objects;

public record CompletedConversationTurn(
        long tenantId,
        long userId,
        long conversationId,
        long agentId,
        long assistantMessageId,
        long assistantSequenceNo,
        String content,
        String modelName,
        ChatModelFinishReason finishReason,
        ChatTokenUsage usage,
        Instant createdAt,
        Instant completedAt
) {

    public CompletedConversationTurn {
        if (tenantId <= 0
                || userId <= 0
                || conversationId <= 0
                || agentId <= 0
                || assistantMessageId <= 0
                || assistantSequenceNo <= 0) {
            throw new IllegalArgumentException(
                    "Completed turn IDs and sequence "
                            + "must be positive"
            );
        }

        ConversationAssistantContentValidator
                .requireValid(content);

        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException(
                    "modelName must not be blank"
            );
        }

        Objects.requireNonNull(
                finishReason,
                "finishReason must not be null"
        );
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