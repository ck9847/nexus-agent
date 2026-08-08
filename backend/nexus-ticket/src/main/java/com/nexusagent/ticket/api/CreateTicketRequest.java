package com.nexusagent.ticket.api;

import com.nexusagent.ticket.domain.TicketPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateTicketRequest(

        @NotBlank
        @Size(max = 255)
        String title,

        @NotBlank
        @Size(max = 10_000)
        String description,

        @NotNull
        TicketPriority priority
) {
}