package com.nexusagent.conversation.internal;

public record ConversationMessageCursor(
        long conversationId,
        long sequenceNo
) {

    public ConversationMessageCursor {
        if (conversationId <= 0) {
            throw new IllegalArgumentException(
                    "conversationId must be positive"
            );
        }

        if (sequenceNo <= 0) {
            throw new IllegalArgumentException(
                    "sequenceNo must be positive"
            );
        }
    }
}