package com.nexusagent.ticket.api;

import com.nexusagent.ticket.domain.TicketPriority;

public record CreateAgentTicketCommand(
        long tenantId,
        long requesterUserId,
        long createdByAgentId,
        long toolExecutionId,
        String title,
        String description,
        TicketPriority priority
) {

    public CreateAgentTicketCommand {
        requirePositive(tenantId, "tenantId");
        requirePositive(
                requesterUserId,
                "requesterUserId"
        );
        requirePositive(
                createdByAgentId,
                "createdByAgentId"
        );
        requirePositive(
                toolExecutionId,
                "toolExecutionId"
        );
    }

    private static void requirePositive(
            long value,
            String field
    ) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    field + " must be positive"
            );
        }
    }
}