package com.nexusagent.ticket.domain;

import java.util.Objects;

public final class InvalidTicketStatusTransitionException
        extends RuntimeException {

    private final TicketStatus currentStatus;
    private final TicketStatus targetStatus;

    public InvalidTicketStatusTransitionException(
            TicketStatus currentStatus,
            TicketStatus targetStatus
    ) {
        super(
                "Cannot transition ticket from "
                        + currentStatus
                        + " to "
                        + targetStatus
        );

        this.currentStatus = Objects.requireNonNull(
                currentStatus,
                "currentStatus must not be null"
        );

        this.targetStatus = Objects.requireNonNull(
                targetStatus,
                "targetStatus must not be null"
        );
    }

    public TicketStatus currentStatus() {
        return currentStatus;
    }

    public TicketStatus targetStatus() {
        return targetStatus;
    }
}