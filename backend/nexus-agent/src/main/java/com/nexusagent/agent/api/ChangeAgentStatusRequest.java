package com.nexusagent.agent.api;

import com.nexusagent.agent.domain.AgentStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ChangeAgentStatusRequest(

        @NotNull(message = "targetStatus is required")
        AgentStatus targetStatus,

        @NotNull(message = "expectedVersion is required")
        @Min(
                value = 0,
                message = "expectedVersion must not be negative"
        )
        Integer expectedVersion
) {
}