package com.nexusagent.tool.internal;

import com.nexusagent.conversation.domain.MessageContentType;
import com.nexusagent.conversation.domain.MessageRole;
import com.nexusagent.conversation.domain.MessageStatus;

import java.time.Instant;

public record ToolCallRequestMessageRow(
        long id,
        long tenantId,
        long conversationId,
        long sequenceNo,
        MessageRole role,
        String content,
        MessageContentType contentType,
        MessageStatus status,
        String modelName,
        String metadataJson,
        Instant createdAt
) {
}
