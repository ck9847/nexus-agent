package com.nexusagent.conversation.api;

public interface StreamConversationTurnService {

    void stream(
            String conversationId,
            String content,
            ConversationTurnStreamHandler handler
    );
}