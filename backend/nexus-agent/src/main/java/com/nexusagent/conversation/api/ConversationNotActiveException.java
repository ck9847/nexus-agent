package com.nexusagent.conversation.api;

import com.nexusagent.conversation.domain.ConversationStatus;

import java.util.Objects;

public final class ConversationNotActiveException
        extends RuntimeException {

    private final ConversationStatus currentStatus;

    public ConversationNotActiveException(
            ConversationStatus currentStatus
    ) {
        super("Conversation is not active");
        this.currentStatus = Objects.requireNonNull(
                currentStatus,
                "currentStatus must not be null"
        );
    }

    public ConversationStatus currentStatus() {
        return currentStatus;
    }
}