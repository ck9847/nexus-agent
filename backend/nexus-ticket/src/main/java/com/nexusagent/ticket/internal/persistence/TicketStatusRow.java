package com.nexusagent.ticket.internal.persistence;

import com.nexusagent.ticket.domain.TicketStatus;

import java.time.Instant;

public record TicketStatusRow(
        long id,
        long tenantId,
        String ticketNo,
        TicketStatus status,
        int version,
        Instant closedAt,
        Instant updatedAt
) {
}