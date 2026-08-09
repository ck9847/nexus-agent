package com.nexusagent.conversation.api;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateConversationRequestValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory()
                    .getValidator();

    @Test
    void shouldAcceptValidRequest() {
        CreateConversationRequest request =
                new CreateConversationRequest(
                        "support-agent",
                        "Production incident",
                        "The payment API returns HTTP 500."
                );

        assertTrue(
                validator.validate(request).isEmpty()
        );
    }

    @Test
    void shouldAllowOptionalTitle() {
        CreateConversationRequest request =
                new CreateConversationRequest(
                        "support-agent",
                        null,
                        "Please create a support ticket."
                );

        assertTrue(
                validator.validate(request).isEmpty()
        );
    }

    @Test
    void shouldRejectInvalidRequiredFields() {
        CreateConversationRequest request =
                new CreateConversationRequest(
                        "INVALID CODE",
                        null,
                        " "
                );

        assertEquals(
                Set.of(
                        "agentCode",
                        "initialMessage"
                ),
                violationPaths(request)
        );
    }

    @Test
    void shouldRejectOversizedFields() {
        CreateConversationRequest request =
                new CreateConversationRequest(
                        "a".repeat(65),
                        "t".repeat(256),
                        "m".repeat(50_001)
                );

        assertEquals(
                Set.of(
                        "agentCode",
                        "title",
                        "initialMessage"
                ),
                violationPaths(request)
        );
    }

    @Test
    void shouldAcceptBoundaryLengths() {
        CreateConversationRequest request =
                new CreateConversationRequest(
                        "a" + "1".repeat(63),
                        "t".repeat(255),
                        "m".repeat(50_000)
                );

        assertTrue(
                validator.validate(request).isEmpty()
        );
    }

    private Set<String> violationPaths(
            CreateConversationRequest request
    ) {
        return validator.validate(request)
                .stream()
                .map(violation ->
                        violation.getPropertyPath()
                                .toString()
                )
                .collect(Collectors.toSet());
    }
}