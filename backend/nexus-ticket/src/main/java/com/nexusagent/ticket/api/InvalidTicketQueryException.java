package com.nexusagent.ticket.api;

public final class InvalidTicketQueryException
        extends RuntimeException {

    public InvalidTicketQueryException(String message) {
        super(message);
    }
}