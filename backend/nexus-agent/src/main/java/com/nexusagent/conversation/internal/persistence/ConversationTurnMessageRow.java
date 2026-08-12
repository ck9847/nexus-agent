package com.nexusagent.conversation.internal.persistence;

import com.nexusagent.conversation.domain.MessageRole;
import com.nexusagent.conversation.domain.MessageStatus;

public record ConversationTurnMessageRow(
        long sequenceNo,
        MessageRole role,
        String content,
        MessageStatus status
) {
}