package com.nexusagent.tool.internal;

import com.nexusagent.ticket.domain.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CreateTicketToolOutputTest {

    @Test
    void shouldAcceptValidOutputAndTrimTicketNo() {
        CreateTicketToolOutput output =
                new CreateTicketToolOutput(
                        "9001",
                        "  TKT-A1  ",
                        TicketStatus.OPEN
                );

        assertEquals("9001", output.ticketId());
        assertEquals("TKT-A1", output.ticketNo());
        assertEquals(TicketStatus.OPEN, output.status());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abc",
            "0",
            "-1",
            "1.5",
            "9001 ",
            ""
    })
    void shouldRejectInvalidTicketIds(String ticketId) {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new CreateTicketToolOutput(
                                ticketId,
                                "TKT-A1",
                                TicketStatus.OPEN
                        )
                );

        assertEquals(
                "ticketId must be a positive integer",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectNullTicketId() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new CreateTicketToolOutput(
                                null,
                                "TKT-A1",
                                TicketStatus.OPEN
                        )
                );

        assertEquals(
                "ticketId must be a positive integer",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectNullTicketNo() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new CreateTicketToolOutput(
                                "9001",
                                null,
                                TicketStatus.OPEN
                        )
                );

        assertEquals(
                "ticketNo must not be null",
                exception.getMessage()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void shouldRejectBlankTicketNo(String ticketNo) {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new CreateTicketToolOutput(
                                "9001",
                                ticketNo,
                                TicketStatus.OPEN
                        )
                );

        assertEquals(
                "ticketNo must not be blank",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectNullStatus() {
        NullPointerException exception =
                assertThrows(
                        NullPointerException.class,
                        () -> new CreateTicketToolOutput(
                                "9001",
                                "TKT-A1",
                                null
                        )
                );

        assertEquals(
                "status must not be null",
                exception.getMessage()
        );
    }
}
