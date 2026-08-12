package com.nexusagent.conversation.api;

public final class
ConversationTurnCapacityExceededException
        extends RuntimeException {

    public ConversationTurnCapacityExceededException(
            Throwable cause
    ) {
        super(
                "Conversation turn capacity "
                        + "is temporarily unavailable",
                cause
        );
    }
}