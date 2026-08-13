package com.nexusagent.tool.internal;

import com.nexusagent.ticket.domain.TicketStatus;

public record ExecuteCreateTicketToolResult(
        long toolExecutionId,
        String ticketId,
        String ticketNo,
        TicketStatus ticketStatus,
        long resultMessageId,
        boolean replayed
) {
}
