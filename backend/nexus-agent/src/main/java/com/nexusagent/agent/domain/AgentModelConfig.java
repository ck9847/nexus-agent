package com.nexusagent.agent.domain;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;

public record AgentModelConfig(

        @DecimalMin(
                value = "0.0",
                message = "temperature must be at least 0.0"
        )
        @DecimalMax(
                value = "2.0",
                message = "temperature must not exceed 2.0"
        )
        BigDecimal temperature,

        @DecimalMin(
                value = "0.0",
                message = "topP must be at least 0.0"
        )
        @DecimalMax(
                value = "1.0",
                message = "topP must not exceed 1.0"
        )
        BigDecimal topP,

        @Min(
                value = 1,
                message = "maxOutputTokens must be positive"
        )
        @Max(
                value = 131_072,
                message = "maxOutputTokens must not exceed 131072"
        )
        Integer maxOutputTokens
) {
}