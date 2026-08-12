package com.nexusagent.conversation.internal.persistence;

import com.nexusagent.conversation.domain.ConversationStatus;

public record ConversationTurnStateRow(
        long id,
        long tenantId,
        long userId,
        long agentId,
        ConversationStatus status,
        long nextMessageSequence,
        int version
) {
}