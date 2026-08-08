package com.nexusagent.ticket.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Base36TicketNumberGeneratorTest {

    private final Base36TicketNumberGenerator generator =
            new Base36TicketNumberGenerator();

    @Test
    void shouldGenerateUppercaseBase36TicketNumber() {
        assertEquals("TKT-Z", generator.generate(35));
        assertEquals("TKT-10", generator.generate(36));
    }

    @Test
    void shouldRejectNonPositiveTicketId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> generator.generate(0)
        );
    }
}