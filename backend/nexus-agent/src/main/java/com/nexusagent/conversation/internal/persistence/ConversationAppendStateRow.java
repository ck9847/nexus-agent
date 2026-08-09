package com.nexusagent.conversation.internal.persistence;

import com.nexusagent.conversation.domain.ConversationStatus;

public record ConversationAppendStateRow(
        long id,
        long tenantId,
        long userId,
        ConversationStatus status,
        long nextMessageSequence,
        int version
) {
}