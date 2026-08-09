package com.nexusagent.conversation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateConversationRequest(

        @NotBlank
        @Size(max = 64)
        @Pattern(
                regexp = "^[a-z][a-z0-9-]{2,63}$",
                message = "agentCode must be 3 to 64 lowercase "
                        + "letters, digits or hyphens and "
                        + "start with a letter"
        )
        String agentCode,

        @Size(max = 255)
        String title,

        @NotBlank
        @Size(max = 50_000)
        String initialMessage
) {
}