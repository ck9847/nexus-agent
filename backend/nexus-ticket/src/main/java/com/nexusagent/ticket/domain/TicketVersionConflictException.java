package com.nexusagent.ticket.domain;

public final class TicketVersionConflictException
        extends RuntimeException {

    public TicketVersionConflictException() {
        super(
                "Ticket was modified by another request"
        );
    }
}