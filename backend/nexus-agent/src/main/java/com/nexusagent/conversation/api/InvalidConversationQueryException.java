package com.nexusagent.conversation.api;

public final class InvalidConversationQueryException
        extends RuntimeException {

    public InvalidConversationQueryException(
            String message
    ) {
        super(message);
    }
}