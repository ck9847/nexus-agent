package com.nexusagent.conversation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AppendUserMessageRequest(

        @NotBlank
        @Size(max = 50_000)
        String content
) {
}