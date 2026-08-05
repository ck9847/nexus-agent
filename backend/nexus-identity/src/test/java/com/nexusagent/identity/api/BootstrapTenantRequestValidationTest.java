package com.nexusagent.identity.api;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapTenantRequestValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptValidRequest() {
        BootstrapTenantRequest request = new BootstrapTenantRequest(
                "acme-corp",
                "Acme Corporation",
                "admin",
                "admin@acme.example",
                "StrongPassword123!"
        );

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void shouldRejectInvalidRequest() {
        BootstrapTenantRequest request = new BootstrapTenantRequest(
                "INVALID CODE",
                "",
                "a",
                "not-an-email",
                "short"
        );

        Set<String> invalidFields = validator.validate(request)
                .stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertTrue(invalidFields.contains("tenantCode"));
        assertTrue(invalidFields.contains("tenantName"));
        assertTrue(invalidFields.contains("adminUsername"));
        assertTrue(invalidFields.contains("adminEmail"));
        assertTrue(invalidFields.contains("adminPassword"));
    }
}