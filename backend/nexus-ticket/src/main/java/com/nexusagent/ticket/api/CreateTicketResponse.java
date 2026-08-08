package com.nexusagent.ticket.api;

import com.nexusagent.ticket.domain.TicketStatus;

public record CreateTicketResponse(
        String ticketId,
        String ticketNo,
        TicketStatus status
) {
}