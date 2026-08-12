package com.nexusagent.conversation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StreamConversationTurnRequest(
        @NotBlank
        @Size(max = 50_000)
        String content
) {
}