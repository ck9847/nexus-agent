package com.nexusagent.conversation.api;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppendUserMessageRequestValidationTest {

    private static final int MAX_CONTENT_LENGTH = 50_000;

    private final Validator validator =
            Validation.buildDefaultValidatorFactory()
                    .getValidator();

    @Test
    void shouldAcceptValidContent() {
        AppendUserMessageRequest request =
                new AppendUserMessageRequest(
                        "  Hello, please help with this issue.  "
                );

        assertTrue(
                validator.validate(request).isEmpty()
        );
    }

    @Test
    void shouldAcceptExactlyMaximumLength() {
        AppendUserMessageRequest request =
                new AppendUserMessageRequest(
                        "x".repeat(MAX_CONTENT_LENGTH)
                );

        assertTrue(
                validator.validate(request).isEmpty()
        );
    }

    @Test
    void shouldRejectNullBlankAndEmptyContent() {
        for (String content :
                new String[]{null, "", "   "}) {
            AppendUserMessageRequest request =
                    new AppendUserMessageRequest(
                            content
                    );

            assertEquals(
                    Set.of("content"),
                    violationPaths(request)
            );
        }
    }

    @Test
    void shouldRejectContentOverMaximumLength() {
        AppendUserMessageRequest request =
                new AppendUserMessageRequest(
                        "x".repeat(
                                MAX_CONTENT_LENGTH + 1
                        )
                );

        assertEquals(
                Set.of("content"),
                violationPaths(request)
        );
    }

    private Set<String> violationPaths(
            AppendUserMessageRequest request
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
