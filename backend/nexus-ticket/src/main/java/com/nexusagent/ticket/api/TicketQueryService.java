package com.nexusagent.ticket.api;

public interface TicketQueryService {

    TicketDetailResponse getByTicketNo(
            String ticketNo
    );

    TicketListResponse list(
            TicketListQuery query
    );
}