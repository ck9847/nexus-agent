package com.nexusagent.conversation.api;

public interface ConversationQueryService {

    ConversationDetailResponse getById(
            String conversationId
    );

    ConversationMessagesResponse listMessages(
            String conversationId,
            ConversationMessagesQuery query
    );
}