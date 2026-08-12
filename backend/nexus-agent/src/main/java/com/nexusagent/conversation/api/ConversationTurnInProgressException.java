package com.nexusagent.conversation.api;

public final class ConversationTurnInProgressException
        extends RuntimeException {

    public ConversationTurnInProgressException() {
        super("A conversation turn is already in progress");
    }
}