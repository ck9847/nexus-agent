package com.nexusagent.ticket.internal.persistence;

import com.nexusagent.ticket.domain.TicketPriority;
import com.nexusagent.ticket.domain.TicketSource;
import com.nexusagent.ticket.domain.TicketStatus;

public record TicketRow(
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
        int version
) {
}