package com.nexusagent.tool.internal;

import com.nexusagent.ticket.domain.TicketStatus;

import java.util.Objects;

public record CreateTicketToolOutput(
        String ticketId,
        String ticketNo,
        TicketStatus status
) {

    public CreateTicketToolOutput {
        long parsedTicketId;

        try {
            parsedTicketId = Long.parseLong(ticketId);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "ticketId must be a positive integer"
            );
        }

        if (parsedTicketId <= 0) {
            throw new IllegalArgumentException(
                    "ticketId must be a positive integer"
            );
        }

        if (ticketNo == null) {
            throw new IllegalArgumentException(
                    "ticketNo must not be null"
            );
        }

        ticketNo = ticketNo.trim();

        if (ticketNo.isBlank()) {
            throw new IllegalArgumentException(
                    "ticketNo must not be blank"
            );
        }

        Objects.requireNonNull(
                status,
                "status must not be null"
        );
    }
}
