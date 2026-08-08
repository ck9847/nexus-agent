package com.nexusagent.ticket.spi;

public interface TicketNumberGenerator {

    String generate(long ticketId);
}