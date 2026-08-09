package com.nexusagent.ticket.api;

import com.nexusagent.ticket.domain.TicketStatus;

import java.time.Instant;

public record ChangeTicketStatusResponse(
        String ticketId,
        String ticketNo,
        TicketStatus previousStatus,
        TicketStatus currentStatus,
        int version,
        Instant closedAt,
        Instant updatedAt
) {
}