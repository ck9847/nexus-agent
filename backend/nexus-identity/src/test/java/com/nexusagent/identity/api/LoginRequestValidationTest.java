package com.nexusagent.identity.api;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginRequestValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptValidLoginRequest() {
        LoginRequest request = new LoginRequest(
                "acme-corp",
                "admin",
                "StrongPassword123!"
        );

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void shouldRejectInvalidLoginRequest() {
        LoginRequest request = new LoginRequest(
                "INVALID CODE",
                "a",
                ""
        );

        Set<String> invalidFields = validator.validate(request)
                .stream()
                .map(violation ->
                        violation.getPropertyPath().toString()
                )
                .collect(Collectors.toSet());

        assertTrue(invalidFields.contains("tenantCode"));
        assertTrue(invalidFields.contains("username"));
        assertTrue(invalidFields.contains("password"));
    }
}