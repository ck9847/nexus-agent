package com.nexusagent.ticket.api;

import com.nexusagent.ticket.domain.TicketStatus;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeTicketStatusRequestValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory()
                    .getValidator();

    @Test
    void shouldRejectMissingFields() {
        ChangeTicketStatusRequest request =
                new ChangeTicketStatusRequest(
                        null,
                        null
                );

        Set<String> fields = validator
                .validate(request)
                .stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());

        assertEquals(
                Set.of(
                        "targetStatus",
                        "expectedVersion"
                ),
                fields
        );
    }

    @Test
    void shouldRejectNegativeVersion() {
        ChangeTicketStatusRequest request =
                new ChangeTicketStatusRequest(
                        TicketStatus.IN_PROGRESS,
                        -1
                );

        Set<String> fields = validator
                .validate(request)
                .stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());

        assertEquals(
                Set.of("expectedVersion"),
                fields
        );
    }

    @Test
    void shouldAcceptValidRequest() {
        ChangeTicketStatusRequest request =
                new ChangeTicketStatusRequest(
                        TicketStatus.IN_PROGRESS,
                        0
                );

        assertTrue(
                validator.validate(request).isEmpty()
        );
    }
}