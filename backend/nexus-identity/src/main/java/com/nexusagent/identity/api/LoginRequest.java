package com.nexusagent.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(

        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "^[a-z][a-z0-9-]{2,63}$")
        String tenantCode,

        @NotBlank
        @Size(min = 3, max = 64)
        @Pattern(regexp = "^[A-Za-z][A-Za-z0-9._-]{2,63}$")
        String username,

        @NotBlank
        @Size(max = 72)
        String password
) {
}