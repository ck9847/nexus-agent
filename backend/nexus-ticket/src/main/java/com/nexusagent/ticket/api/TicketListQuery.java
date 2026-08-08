package com.nexusagent.ticket.api;

import com.nexusagent.ticket.domain.TicketPriority;
import com.nexusagent.ticket.domain.TicketStatus;

public record TicketListQuery(
        TicketStatus status,
        TicketPriority priority,
        int limit,
        String cursor
) {

    private static final int MAX_LIMIT = 100;

    public TicketListQuery {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidTicketQueryException(
                    "limit must be between 1 and "
                            + MAX_LIMIT
            );
        }

        if (cursor != null) {
            cursor = cursor.trim();

            if (cursor.isBlank()) {
                cursor = null;
            }
        }
    }
}