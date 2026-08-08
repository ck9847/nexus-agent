package com.nexusagent.ticket.api;

public final class TicketNotFoundException
        extends RuntimeException {

    public TicketNotFoundException() {
        super("Ticket not found");
    }
}