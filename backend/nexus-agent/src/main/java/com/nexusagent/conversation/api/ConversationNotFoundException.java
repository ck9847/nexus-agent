package com.nexusagent.conversation.api;

public final class ConversationNotFoundException
        extends RuntimeException {

    public ConversationNotFoundException() {
        super("Conversation not found");
    }
}