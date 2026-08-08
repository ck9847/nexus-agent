package com.nexusagent.ticket.internal;

import com.nexusagent.ticket.spi.TicketNumberGenerator;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class Base36TicketNumberGenerator
        implements TicketNumberGenerator {

    private static final String PREFIX = "TKT-";

    @Override
    public String generate(long ticketId) {
        if (ticketId <= 0) {
            throw new IllegalArgumentException(
                    "ticketId must be positive"
            );
        }

        return PREFIX
                + Long.toString(ticketId, 36)
                .toUpperCase(Locale.ROOT);
    }
}