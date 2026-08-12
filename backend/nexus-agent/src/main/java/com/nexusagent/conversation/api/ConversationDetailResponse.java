package com.nexusagent.conversation.api;

import com.nexusagent.conversation.domain.ConversationStatus;

import java.time.Instant;

public record ConversationDetailResponse(
        String conversationId,
        String agentId,
        String title,
        ConversationStatus status,
        Instant lastMessageAt,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}