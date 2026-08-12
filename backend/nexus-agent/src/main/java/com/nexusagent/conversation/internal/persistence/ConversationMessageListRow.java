package com.nexusagent.conversation.internal.persistence;

import com.nexusagent.conversation.domain.MessageContentType;
import com.nexusagent.conversation.domain.MessageRole;
import com.nexusagent.conversation.domain.MessageStatus;

import java.time.Instant;

public record ConversationMessageListRow(
        long id,
        long tenantId,
        long conversationId,
        long sequenceNo,
        MessageRole role,
        String content,
        MessageContentType contentType,
        MessageStatus status,
        Instant createdAt
) {
}