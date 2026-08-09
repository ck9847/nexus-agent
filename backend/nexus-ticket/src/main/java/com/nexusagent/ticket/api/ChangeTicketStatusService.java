package com.nexusagent.ticket.api;

public interface ChangeTicketStatusService {

    ChangeTicketStatusResponse changeStatus(
            String ticketNo,
            ChangeTicketStatusRequest request
    );
}