package com.nexusagent.ticket.internal;

import java.time.Instant;
import java.util.Objects;

public record TicketPageCursor(
        Instant createdAt,
        long ticketId
) {

    public TicketPageCursor {
        Objects.requireNonNull(
                createdAt,
                "createdAt must not be null"
        );

        if (ticketId <= 0) {
            throw new IllegalArgumentException(
                    "ticketId must be positive"
            );
        }
    }
}