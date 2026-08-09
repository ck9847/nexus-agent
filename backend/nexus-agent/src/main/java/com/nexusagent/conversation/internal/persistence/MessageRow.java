package com.nexusagent.conversation.internal.persistence;

import com.nexusagent.conversation.domain.MessageContentType;
import com.nexusagent.conversation.domain.MessageRole;
import com.nexusagent.conversation.domain.MessageStatus;

import java.time.Instant;

public record MessageRow(
        long id,
        long tenantId,
        long conversationId,
        long sequenceNo,
        MessageRole role,
        String content,
        MessageContentType contentType,
        MessageStatus status,
        String modelName,
        Integer promptTokens,
        Integer completionTokens,
        String metadataJson,
        Instant createdAt
) {
}