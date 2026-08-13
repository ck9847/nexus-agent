package com.nexusagent.tool.internal;

import com.nexusagent.ticket.domain.TicketPriority;

public record CreateTicketToolArguments(
        String title,
        String description,
        TicketPriority priority
) {
}
