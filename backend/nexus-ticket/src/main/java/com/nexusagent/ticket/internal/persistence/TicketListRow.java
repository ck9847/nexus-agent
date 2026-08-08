package com.nexusagent.ticket.internal.persistence;

import com.nexusagent.ticket.domain.TicketPriority;
import com.nexusagent.ticket.domain.TicketSource;
import com.nexusagent.ticket.domain.TicketStatus;

import java.time.Instant;

public record TicketListRow(
        long id,
        long tenantId,
        String ticketNo,
        String title,
        TicketPriority priority,
        TicketStatus status,
        TicketSource source,
        long requesterUserId,
        Long assigneeUserId,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}