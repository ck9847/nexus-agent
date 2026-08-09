package com.nexusagent.agent.api;

import com.nexusagent.agent.domain.AgentModelConfig;
import com.nexusagent.agent.domain.AgentModelProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAgentRequest(

        @NotBlank
        @Size(max = 64)
        @Pattern(
                regexp = "^[a-z][a-z0-9-]{2,63}$",
                message = "code must be 3 to 64 lowercase "
                        + "letters, digits or hyphens and "
                        + "start with a letter"
        )
        String code,

        @NotBlank
        @Size(max = 128)
        String name,

        @Size(max = 500)
        String description,

        @NotBlank
        @Size(max = 50_000)
        String systemPrompt,

        @NotNull
        AgentModelProvider modelProvider,

        @NotBlank
        @Size(max = 128)
        String modelName,

        @Valid
        AgentModelConfig modelConfig
) {
}