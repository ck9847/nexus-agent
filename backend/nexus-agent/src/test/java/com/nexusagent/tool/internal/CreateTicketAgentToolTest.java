package com.nexusagent.tool.internal;

import com.nexusagent.ticket.api.CreateAgentTicketCommand;
import com.nexusagent.ticket.api.CreateAgentTicketService;
import com.nexusagent.ticket.api.CreateTicketResponse;
import com.nexusagent.ticket.domain.TicketPriority;
import com.nexusagent.ticket.domain.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateTicketAgentToolTest {

    private static final long TENANT_ID = 202L;
    private static final long REQUESTER_USER_ID = 101L;
    private static final long CONVERSATION_ID = 901L;
    private static final long AGENT_ID = 500L;
    private static final long REQUEST_MESSAGE_ID = 1001L;
    private static final long TOOL_EXECUTION_ID = 7001L;

    @Mock
    private CreateAgentTicketService ticketService;

    private CreateTicketAgentTool tool;

    @BeforeEach
    void setUp() {
        tool = new CreateTicketAgentTool(ticketService);
    }

    @Test
    void shouldBuildCommandFromContextAndArguments() {
        CreateTicketResponse expected =
                new CreateTicketResponse(
                        "8001",
                        "TKT-A1",
                        TicketStatus.OPEN
                );

        when(ticketService.create(
                any(CreateAgentTicketCommand.class)
        )).thenReturn(expected);

        CreateTicketResponse response =
                tool.execute(
                        context(),
                        arguments()
                );

        ArgumentCaptor<CreateAgentTicketCommand> captor =
                ArgumentCaptor.forClass(
                        CreateAgentTicketCommand.class
                );

        verify(ticketService).create(captor.capture());

        CreateAgentTicketCommand command =
                captor.getValue();

        assertEquals(TENANT_ID, command.tenantId());
        assertEquals(
                REQUESTER_USER_ID,
                command.requesterUserId()
        );
        assertEquals(AGENT_ID, command.createdByAgentId());
        assertEquals(
                TOOL_EXECUTION_ID,
                command.toolExecutionId()
        );
        assertEquals(
                "  Server unavailable  ",
                command.title()
        );
        assertEquals(
                "  Cannot connect to production.  ",
                command.description()
        );
        assertEquals(
                TicketPriority.URGENT,
                command.priority()
        );

        assertSame(expected, response);
    }

    @Test
    void shouldRejectNullContextWithoutCallingService() {
        assertThrows(
                NullPointerException.class,
                () -> tool.execute(
                        null,
                        arguments()
                )
        );

        verify(ticketService, never()).create(
                any(CreateAgentTicketCommand.class)
        );
    }

    @Test
    void shouldRejectNullArgumentsWithoutCallingService() {
        assertThrows(
                NullPointerException.class,
                () -> tool.execute(
                        context(),
                        null
                )
        );

        verify(ticketService, never()).create(
                any(CreateAgentTicketCommand.class)
        );
    }

    private static AgentToolExecutionContext context() {
        return new AgentToolExecutionContext(
                TENANT_ID,
                REQUESTER_USER_ID,
                CONVERSATION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                TOOL_EXECUTION_ID,
                "call-1"
        );
    }

    private static CreateTicketToolArguments arguments() {
        return new CreateTicketToolArguments(
                "  Server unavailable  ",
                "  Cannot connect to production.  ",
                TicketPriority.URGENT
        );
    }
}
