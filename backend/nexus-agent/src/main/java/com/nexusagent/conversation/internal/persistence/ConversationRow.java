package com.nexusagent.conversation.internal.persistence;

import com.nexusagent.conversation.domain.ConversationStatus;

import java.time.Instant;

public record ConversationRow(
        long id,
        long tenantId,
        long userId,
        long agentId,
        String title,
        ConversationStatus status,
        Instant lastMessageAt,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}