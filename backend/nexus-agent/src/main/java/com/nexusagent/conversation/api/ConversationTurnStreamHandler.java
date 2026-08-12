package com.nexusagent.conversation.api;

@FunctionalInterface
public interface ConversationTurnStreamHandler {

    void onEvent(ConversationTurnStreamEvent event);

    default boolean isCancellationRequested() {
        return false;
    }
}