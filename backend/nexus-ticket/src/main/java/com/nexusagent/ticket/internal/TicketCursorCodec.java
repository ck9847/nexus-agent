package com.nexusagent.ticket.internal;

public interface TicketCursorCodec {

    String encode(TicketPageCursor cursor);

    TicketPageCursor decode(String cursor);
}