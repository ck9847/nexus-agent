package com.nexusagent.ticket.internal.persistence;

import com.nexusagent.ticket.domain.TicketPriority;
import com.nexusagent.ticket.domain.TicketSource;
import com.nexusagent.ticket.domain.TicketStatus;

import java.time.Instant;

public record TicketDetailRow(
        long id,
        long tenantId,
        String ticketNo,
        String title,
        String description,
        TicketPriority priority,
        TicketStatus status,
        TicketSource source,
        long requesterUserId,
        Long assigneeUserId,
        Long createdByAgentId,
        int version,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt
) {
}