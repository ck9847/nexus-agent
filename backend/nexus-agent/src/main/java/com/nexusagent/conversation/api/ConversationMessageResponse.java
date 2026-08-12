package com.nexusagent.conversation.api;

import com.nexusagent.conversation.domain.MessageContentType;
import com.nexusagent.conversation.domain.MessageRole;
import com.nexusagent.conversation.domain.MessageStatus;

import java.time.Instant;

public record ConversationMessageResponse(
        String messageId,
        long sequenceNo,
        MessageRole role,
        String content,
        MessageContentType contentType,
        MessageStatus status,
        Instant createdAt
) {
}