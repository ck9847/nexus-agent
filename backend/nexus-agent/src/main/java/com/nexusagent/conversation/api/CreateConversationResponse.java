package com.nexusagent.conversation.api;

import com.nexusagent.conversation.domain.ConversationStatus;

import java.time.Instant;

public record CreateConversationResponse(
        String conversationId,
        String agentId,
        String agentCode,
        String title,
        ConversationStatus status,
        int version,
        Instant lastMessageAt,
        Instant createdAt,
        Instant updatedAt,
        CreatedMessageResponse initialMessage
) {
}