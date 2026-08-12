package com.nexusagent.conversation.internal;

public interface PrepareConversationTurnService {

    PreparedConversationTurn prepare(
            String conversationId,
            String content
    );
}