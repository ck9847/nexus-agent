package com.nexusagent.agent.api;

import com.nexusagent.agent.domain.AgentStatus;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeAgentStatusRequestValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory()
                    .getValidator();

    @Test
    void shouldRejectMissingFields() {
        ChangeAgentStatusRequest request =
                new ChangeAgentStatusRequest(
                        null,
                        null
                );

        assertEquals(
                Set.of(
                        "targetStatus",
                        "expectedVersion"
                ),
                violationPaths(request)
        );
    }

    @Test
    void shouldRejectNegativeVersion() {
        ChangeAgentStatusRequest request =
                new ChangeAgentStatusRequest(
                        AgentStatus.ACTIVE,
                        -1
                );

        assertEquals(
                Set.of("expectedVersion"),
                violationPaths(request)
        );
    }

    @Test
    void shouldAcceptValidRequest() {
        ChangeAgentStatusRequest request =
                new ChangeAgentStatusRequest(
                        AgentStatus.ACTIVE,
                        0
                );

        assertTrue(
                validator.validate(request).isEmpty()
        );
    }

    private Set<String> violationPaths(
            ChangeAgentStatusRequest request
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