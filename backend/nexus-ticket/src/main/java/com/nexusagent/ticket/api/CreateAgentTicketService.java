package com.nexusagent.ticket.api;

public interface CreateAgentTicketService {

    CreateTicketResponse create(
            CreateAgentTicketCommand command
    );
}