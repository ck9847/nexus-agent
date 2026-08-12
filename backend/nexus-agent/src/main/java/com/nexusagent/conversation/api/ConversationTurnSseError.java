package com.nexusagent.conversation.api;

public record ConversationTurnSseError(
        String errorCode,
        String message,
        boolean retryable
) {

    public ConversationTurnSseError {
        if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException(
                    "errorCode must not be blank"
            );
        }

        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException(
                    "message must not be blank"
            );
        }
    }
}