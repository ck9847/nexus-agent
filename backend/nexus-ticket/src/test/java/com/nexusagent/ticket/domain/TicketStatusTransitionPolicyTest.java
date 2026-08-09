package com.nexusagent.ticket.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketStatusTransitionPolicyTest {

    @ParameterizedTest
    @CsvSource({
            "OPEN, IN_PROGRESS",
            "IN_PROGRESS, RESOLVED",
            "RESOLVED, IN_PROGRESS",
            "RESOLVED, CLOSED"
    })
    void shouldAllowValidTransitions(
            TicketStatus currentStatus,
            TicketStatus targetStatus
    ) {
        assertTrue(
                TicketStatusTransitionPolicy.isAllowed(
                        currentStatus,
                        targetStatus
                )
        );

        assertDoesNotThrow(
                () -> TicketStatusTransitionPolicy
                        .requireAllowed(
                                currentStatus,
                                targetStatus
                        )
        );
    }

    @ParameterizedTest
    @CsvSource({
            "OPEN, OPEN",
            "OPEN, RESOLVED",
            "OPEN, CLOSED",
            "IN_PROGRESS, OPEN",
            "IN_PROGRESS, IN_PROGRESS",
            "IN_PROGRESS, CLOSED",
            "RESOLVED, OPEN",
            "RESOLVED, RESOLVED",
            "CLOSED, OPEN",
            "CLOSED, IN_PROGRESS",
            "CLOSED, RESOLVED",
            "CLOSED, CLOSED"
    })
    void shouldRejectInvalidTransitions(
            TicketStatus currentStatus,
            TicketStatus targetStatus
    ) {
        assertFalse(
                TicketStatusTransitionPolicy.isAllowed(
                        currentStatus,
                        targetStatus
                )
        );

        assertThrows(
                InvalidTicketStatusTransitionException.class,
                () -> TicketStatusTransitionPolicy
                        .requireAllowed(
                                currentStatus,
                                targetStatus
                        )
        );
    }
}