package com.nexusagent.conversation.api;

import java.time.Instant;

public record AppendUserMessageResponse(
        String conversationId,
        int conversationVersion,
        Instant lastMessageAt,
        CreatedMessageResponse message
) {
}