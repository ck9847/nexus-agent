package com.nexusagent.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BootstrapTenantRequest(

        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "^[a-z][a-z0-9-]{2,63}$")
        String tenantCode,

        @NotBlank
        @Size(max = 128)
        String tenantName,

        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "^[A-Za-z][A-Za-z0-9._-]{2,63}$")
        String adminUsername,

        @NotBlank
        @Email
        @Size(max = 255)
        String adminEmail,

        @NotBlank
        @Size(min = 12, max = 72)
        String adminPassword
) {
}