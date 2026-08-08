package com.nexusagent.ticket.api;

import com.nexusagent.ticket.domain.TicketPriority;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateTicketRequestValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory()
                    .getValidator();

    @Test
    void shouldAcceptValidCreateTicketRequest() {
        CreateTicketRequest request =
                new CreateTicketRequest(
                        "Production server is unavailable",
                        "The production API cannot be reached.",
                        TicketPriority.HIGH
                );

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void shouldRejectInvalidCreateTicketRequest() {
        CreateTicketRequest request =
                new CreateTicketRequest(
                        "",
                        "",
                        null
                );

        Set<String> invalidFields =
                validator.validate(request)
                        .stream()
                        .map(violation ->
                                violation.getPropertyPath()
                                        .toString()
                        )
                        .collect(Collectors.toSet());

        assertTrue(invalidFields.contains("title"));
        assertTrue(invalidFields.contains("description"));
        assertTrue(invalidFields.contains("priority"));
    }
}