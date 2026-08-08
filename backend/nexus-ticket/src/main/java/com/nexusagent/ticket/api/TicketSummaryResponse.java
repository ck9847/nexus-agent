package com.nexusagent.ticket.api;

import com.nexusagent.ticket.domain.TicketPriority;
import com.nexusagent.ticket.domain.TicketSource;
import com.nexusagent.ticket.domain.TicketStatus;

import java.time.Instant;

public record TicketSummaryResponse(
        String ticketId,
        String ticketNo,
        String title,
        TicketPriority priority,
        TicketStatus status,
        TicketSource source,
        String requesterUserId,
        String assigneeUserId,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}