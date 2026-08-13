package com.nexusagent.ticket.internal;

import com.nexusagent.ticket.domain.TicketPriority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TicketCreationNormalizerTest {

    @Test
    void shouldTrimTitleAndDescription() {
        NormalizedTicketCreation normalized =
                TicketCreationNormalizer.normalize(
                        "  Server unavailable  ",
                        "  Cannot connect to production.  ",
                        TicketPriority.HIGH
                );

        assertEquals(
                "Server unavailable",
                normalized.title()
        );
        assertEquals(
                "Cannot connect to production.",
                normalized.description()
        );
        assertEquals(
                TicketPriority.HIGH,
                normalized.priority()
        );
    }

    @Test
    void shouldAcceptMaximumLengths() {
        NormalizedTicketCreation normalized =
                TicketCreationNormalizer.normalize(
                        "a".repeat(255),
                        "b".repeat(10_000),
                        TicketPriority.LOW
                );

        assertEquals(255, normalized.title().length());
        assertEquals(
                10_000,
                normalized.description().length()
        );
    }

    @ParameterizedTest
    @MethodSource("invalidTitles")
    void shouldRejectInvalidTitles(
            String title,
            String message
    ) {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> TicketCreationNormalizer
                                .normalize(
                                        title,
                                        "description",
                                        TicketPriority.HIGH
                                )
                );

        assertEquals(message, exception.getMessage());
    }

    @ParameterizedTest
    @MethodSource("invalidDescriptions")
    void shouldRejectInvalidDescriptions(
            String description,
            String message
    ) {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> TicketCreationNormalizer
                                .normalize(
                                        "title",
                                        description,
                                        TicketPriority.HIGH
                                )
                );

        assertEquals(message, exception.getMessage());
    }

    @Test
    void shouldRejectNullPriority() {
        NullPointerException exception =
                assertThrows(
                        NullPointerException.class,
                        () -> TicketCreationNormalizer
                                .normalize(
                                        "title",
                                        "description",
                                        null
                                )
                );

        assertEquals(
                "priority must not be null",
                exception.getMessage()
        );
    }

    private static Stream<Arguments> invalidTitles() {
        return Stream.of(
                Arguments.of(
                        null,
                        "title must not be null"
                ),
                Arguments.of("", "title must not be blank"),
                Arguments.of(
                        "   ",
                        "title must not be blank"
                ),
                Arguments.of(
                        "a".repeat(256),
                        "title must not exceed "
                                + "255 characters"
                )
        );
    }

    private static Stream<Arguments> invalidDescriptions() {
        return Stream.of(
                Arguments.of(
                        null,
                        "description must not be null"
                ),
                Arguments.of(
                        "",
                        "description must not be blank"
                ),
                Arguments.of(
                        "   ",
                        "description must not be blank"
                ),
                Arguments.of(
                        "b".repeat(10_001),
                        "description must not exceed "
                                + "10000 characters"
                )
        );
    }
}
