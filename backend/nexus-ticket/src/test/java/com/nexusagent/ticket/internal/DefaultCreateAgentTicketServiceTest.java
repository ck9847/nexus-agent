package com.nexusagent.ticket.internal;

import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.common.id.IdGenerator;
import com.nexusagent.ticket.api.CreateAgentTicketCommand;
import com.nexusagent.ticket.api.CreateTicketResponse;
import com.nexusagent.ticket.domain.TicketPriority;
import com.nexusagent.ticket.domain.TicketSource;
import com.nexusagent.ticket.domain.TicketStatus;
import com.nexusagent.ticket.internal.persistence.TicketMapper;
import com.nexusagent.ticket.internal.persistence.TicketRow;
import com.nexusagent.ticket.spi.TicketNumberGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultCreateAgentTicketServiceTest {

    private static final long TENANT_ID = 202L;
    private static final long REQUESTER_USER_ID = 101L;
    private static final long AGENT_ID = 500L;
    private static final long TOOL_EXECUTION_ID = 7001L;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private TicketNumberGenerator ticketNumberGenerator;

    @Mock
    private TicketMapper ticketMapper;

    @Mock
    private AuditLogWriter auditLogWriter;

    private DefaultCreateAgentTicketService service;

    @BeforeEach
    void setUp() {
        service = new DefaultCreateAgentTicketService(
                idGenerator,
                ticketNumberGenerator,
                ticketMapper,
                auditLogWriter
        );
    }

    @Test
    void shouldCreateAgentTicketFromCommand() {
        stubSuccessfulCreate();

        CreateTicketResponse response =
                service.create(command());

        ArgumentCaptor<TicketRow> ticketCaptor =
                ArgumentCaptor.forClass(TicketRow.class);

        ArgumentCaptor<AuditLogCommand> auditCaptor =
                ArgumentCaptor.forClass(
                        AuditLogCommand.class
                );

        verify(ticketMapper).insert(ticketCaptor.capture());
        verify(auditLogWriter).write(auditCaptor.capture());

        TicketRow ticket = ticketCaptor.getValue();
        AuditLogCommand audit = auditCaptor.getValue();

        assertAll(
                () -> assertEquals(901L, ticket.id()),
                () -> assertEquals(
                        TENANT_ID,
                        ticket.tenantId()
                ),
                () -> assertEquals(
                        "TKT-A1",
                        ticket.ticketNo()
                ),
                () -> assertEquals(
                        "Server unavailable",
                        ticket.title()
                ),
                () -> assertEquals(
                        "Cannot connect to production.",
                        ticket.description()
                ),
                () -> assertEquals(
                        TicketPriority.HIGH,
                        ticket.priority()
                ),
                () -> assertEquals(
                        TicketStatus.OPEN,
                        ticket.status()
                ),
                () -> assertEquals(
                        TicketSource.AGENT,
                        ticket.source()
                ),
                () -> assertEquals(
                        REQUESTER_USER_ID,
                        ticket.requesterUserId()
                ),
                () -> assertNull(ticket.assigneeUserId()),
                () -> assertEquals(
                        AGENT_ID,
                        ticket.createdByAgentId()
                ),
                () -> assertEquals(0, ticket.version())
        );

        assertAll(
                () -> assertEquals(
                        TENANT_ID,
                        audit.tenantId()
                ),
                () -> assertEquals(
                        AuditActorType.AGENT,
                        audit.actorType()
                ),
                () -> assertEquals(
                        AGENT_ID,
                        audit.actorId()
                ),
                () -> assertEquals(
                        "TICKET_CREATED",
                        audit.action()
                ),
                () -> assertEquals(
                        "TICKET",
                        audit.resourceType()
                ),
                () -> assertEquals(
                        901L,
                        audit.resourceId()
                ),
                () -> assertEquals(
                        TOOL_EXECUTION_ID,
                        audit.toolExecutionId()
                ),
                () -> assertEquals(
                        AuditResult.SUCCESS,
                        audit.result()
                )
        );

        Map<String, Object> afterData =
                (Map<String, Object>) audit.afterData();

        assertAll(
                () -> assertEquals(
                        "TKT-A1",
                        afterData.get("ticketNo")
                ),
                () -> assertEquals(
                        "HIGH",
                        afterData.get("priority")
                ),
                () -> assertEquals(
                        "OPEN",
                        afterData.get("status")
                ),
                () -> assertEquals(
                        "AGENT",
                        afterData.get("source")
                ),
                () -> assertEquals(
                        Long.toString(REQUESTER_USER_ID),
                        afterData.get("requesterUserId")
                ),
                () -> assertEquals(
                        Long.toString(AGENT_ID),
                        afterData.get("createdByAgentId")
                ),
                () -> assertEquals(
                        6,
                        afterData.size()
                ),
                () -> assertNull(afterData.get("title")),
                () -> assertNull(
                        afterData.get("description")
                )
        );

        assertAll(
                () -> assertEquals(
                        "901",
                        response.ticketId()
                ),
                () -> assertEquals(
                        "TKT-A1",
                        response.ticketNo()
                ),
                () -> assertEquals(
                        TicketStatus.OPEN,
                        response.status()
                )
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void shouldRejectUnexpectedInsertCountWithoutAuditing(
            int affectedRows
    ) {
        stubGeneratedIds();

        when(ticketMapper.insert(any()))
                .thenReturn(affectedRows);

        assertThrows(
                IllegalStateException.class,
                () -> service.create(command())
        );

        verify(auditLogWriter, never()).write(
                any(AuditLogCommand.class)
        );
    }

    @Test
    void shouldPropagateAuditFailure() {
        stubSuccessfulCreate();

        IllegalStateException failure =
                new IllegalStateException("audit boom");

        doThrow(failure)
                .when(auditLogWriter)
                .write(any(AuditLogCommand.class));

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.create(command())
                );

        assertSame(failure, exception);
    }

    @Test
    void shouldRejectNullCommandWithoutSideEffects() {
        assertThrows(
                NullPointerException.class,
                () -> service.create(null)
        );

        verifyNoInteractions(
                idGenerator,
                ticketNumberGenerator,
                ticketMapper,
                auditLogWriter
        );
    }

    @Test
    void shouldRejectInvalidTextWithoutSideEffects() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.create(
                                new CreateAgentTicketCommand(
                                        TENANT_ID,
                                        REQUESTER_USER_ID,
                                        AGENT_ID,
                                        TOOL_EXECUTION_ID,
                                        "   ",
                                        "description",
                                        TicketPriority.HIGH
                                )
                        )
                );

        assertEquals(
                "title must not be blank",
                exception.getMessage()
        );

        verifyNoInteractions(
                idGenerator,
                ticketNumberGenerator,
                ticketMapper,
                auditLogWriter
        );
    }

    private static CreateAgentTicketCommand command() {
        return new CreateAgentTicketCommand(
                TENANT_ID,
                REQUESTER_USER_ID,
                AGENT_ID,
                TOOL_EXECUTION_ID,
                "  Server unavailable  ",
                "  Cannot connect to production.  ",
                TicketPriority.HIGH
        );
    }

    private void stubGeneratedIds() {
        when(idGenerator.nextId())
                .thenReturn(901L);
        when(ticketNumberGenerator.generate(901L))
                .thenReturn("TKT-A1");
    }

    private void stubSuccessfulCreate() {
        stubGeneratedIds();

        when(ticketMapper.insert(any()))
                .thenReturn(1);
    }
}
