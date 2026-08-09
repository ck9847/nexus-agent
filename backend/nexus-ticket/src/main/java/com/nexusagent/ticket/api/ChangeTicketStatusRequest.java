package com.nexusagent.ticket.api;

import com.nexusagent.ticket.domain.TicketStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ChangeTicketStatusRequest(

        @NotNull(message = "targetStatus is required")
        TicketStatus targetStatus,

        @NotNull(message = "expectedVersion is required")
        @Min(
                value = 0,
                message = "expectedVersion must not be negative"
        )
        Integer expectedVersion
) {
}