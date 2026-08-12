package com.nexusagent.conversation.api;

import com.nexusagent.model.api.ChatModelFinishReason;

import java.time.Instant;
import java.util.Objects;

public sealed interface ConversationTurnStreamEvent
        permits ConversationTurnStreamEvent.Started,
        ConversationTurnStreamEvent.TextDelta,
        ConversationTurnStreamEvent.Completed {

    record Started(
            String conversationId,
            String agentId,
            String userMessageId,
            long userSequenceNo,
            String assistantMessageId,
            long assistantSequenceNo,
            int conversationVersion,
            Instant createdAt
    ) implements ConversationTurnStreamEvent {

        public Started {
            requireText(conversationId, "conversationId");
            requireText(agentId, "agentId");
            requireText(userMessageId, "userMessageId");
            requireText(
                    assistantMessageId,
                    "assistantMessageId"
            );

            if (userSequenceNo <= 0
                    || assistantSequenceNo
                    != userSequenceNo + 1) {
                throw new IllegalArgumentException(
                        "Turn sequences must be positive "
                                + "and consecutive"
                );
            }

            if (conversationVersion <= 0) {
                throw new IllegalArgumentException(
                        "conversationVersion must be positive"
                );
            }

            Objects.requireNonNull(
                    createdAt,
                    "createdAt must not be null"
            );
        }
    }

    record TextDelta(
            String text
    ) implements ConversationTurnStreamEvent {

        public TextDelta {
            Objects.requireNonNull(
                    text,
                    "text must not be null"
            );

            if (text.isEmpty()) {
                throw new IllegalArgumentException(
                        "text must not be empty"
                );
            }
        }
    }

    record Completed(
            String conversationId,
            String agentId,
            String assistantMessageId,
            long assistantSequenceNo,
            int conversationVersion,
            String modelName,
            ChatModelFinishReason finishReason,
            int promptTokens,
            int completionTokens,
            Instant completedAt
    ) implements ConversationTurnStreamEvent {

        public Completed {
            requireText(conversationId, "conversationId");
            requireText(agentId, "agentId");
            requireText(
                    assistantMessageId,
                    "assistantMessageId"
            );
            requireText(modelName, "modelName");

            if (assistantSequenceNo <= 0
                    || conversationVersion <= 0) {
                throw new IllegalArgumentException(
                        "Sequence and version must be positive"
                );
            }

            Objects.requireNonNull(
                    finishReason,
                    "finishReason must not be null"
            );
            Objects.requireNonNull(
                    completedAt,
                    "completedAt must not be null"
            );

            if (promptTokens < 0
                    || completionTokens < 0) {
                throw new IllegalArgumentException(
                        "Token counts must not be negative"
                );
            }
        }
    }

    private static void requireText(
            String value,
            String field
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }
    }
}