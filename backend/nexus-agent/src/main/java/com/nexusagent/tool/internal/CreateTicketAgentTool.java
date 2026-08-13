package com.nexusagent.tool.internal;

import com.nexusagent.ticket.api.CreateAgentTicketCommand;
import com.nexusagent.ticket.api.CreateAgentTicketService;
import com.nexusagent.ticket.api.CreateTicketResponse;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
final class CreateTicketAgentTool {

    private final CreateAgentTicketService ticketService;

    CreateTicketAgentTool(
            CreateAgentTicketService ticketService
    ) {
        this.ticketService = Objects.requireNonNull(
                ticketService
        );
    }

    CreateTicketResponse execute(
            AgentToolExecutionContext context,
            CreateTicketToolArguments arguments
    ) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(arguments);

        return ticketService.create(
                new CreateAgentTicketCommand(
                        context.tenantId(),
                        context.requesterUserId(),
                        context.agentId(),
                        context.toolExecutionId(),
                        arguments.title(),
                        arguments.description(),
                        arguments.priority()
                )
        );
    }
}
