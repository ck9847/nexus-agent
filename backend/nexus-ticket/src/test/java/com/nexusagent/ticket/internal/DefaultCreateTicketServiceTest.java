package com.nexusagent.ticket.internal;

import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.common.id.IdGenerator;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.ticket.api.CreateTicketRequest;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultCreateTicketServiceTest {

    @Mock
    private CurrentActorProvider currentActorProvider;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private TicketNumberGenerator ticketNumberGenerator;

    @Mock
    private TicketMapper ticketMapper;

    @Mock
    private AuditLogWriter auditLogWriter;

    private DefaultCreateTicketService service;

    @BeforeEach
    void setUp() {
        service = new DefaultCreateTicketService(
                currentActorProvider,
                idGenerator,
                ticketNumberGenerator,
                ticketMapper,
                auditLogWriter
        );
    }

    @Test
    void shouldCreateUserTicketWithinCurrentTenant() {
        CurrentActor actor = new CurrentActor(
                101,
                202,
                "admin",
                Set.of("ADMIN")
        );

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(actor);
        when(idGenerator.nextId())
                .thenReturn(901L);
        when(ticketNumberGenerator.generate(901L))
                .thenReturn("TKT-P1");
        when(ticketMapper.insert(any()))
                .thenReturn(1);

        CreateTicketResponse response = service.create(
                new CreateTicketRequest(
                        " Server unavailable ",
                        " Cannot connect to production. ",
                        TicketPriority.HIGH
                )
        );

        ArgumentCaptor<TicketRow> ticketCaptor =
                ArgumentCaptor.forClass(TicketRow.class);

        ArgumentCaptor<AuditLogCommand> auditCaptor =
                ArgumentCaptor.forClass(
                        AuditLogCommand.class
                );

        verify(ticketMapper).insert(
                ticketCaptor.capture()
        );
        verify(auditLogWriter).write(
                auditCaptor.capture()
        );

        TicketRow ticket = ticketCaptor.getValue();
        AuditLogCommand audit = auditCaptor.getValue();

        assertAll(
                () -> assertEquals(901L, ticket.id()),
                () -> assertEquals(202L, ticket.tenantId()),
                () -> assertEquals(
                        "TKT-P1",
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
                        TicketSource.USER,
                        ticket.source()
                ),
                () -> assertEquals(
                        101L,
                        ticket.requesterUserId()
                ),
                () -> assertNull(
                        ticket.assigneeUserId()
                ),
                () -> assertNull(
                        ticket.createdByAgentId()
                ),
                () -> assertEquals(0, ticket.version())
        );

        assertAll(
                () -> assertEquals(
                        202L,
                        audit.tenantId()
                ),
                () -> assertEquals(
                        AuditActorType.USER,
                        audit.actorType()
                ),
                () -> assertEquals(
                        101L,
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
                        AuditResult.SUCCESS,
                        audit.result()
                )
        );

        assertAll(
                () -> assertEquals(
                        "901",
                        response.ticketId()
                ),
                () -> assertEquals(
                        "TKT-P1",
                        response.ticketNo()
                ),
                () -> assertEquals(
                        TicketStatus.OPEN,
                        response.status()
                )
        );
    }

    @Test
    void shouldRejectUnexpectedInsertCountWithoutAuditing() {
        CurrentActor actor = new CurrentActor(
                101,
                202,
                "admin",
                Set.of("ADMIN")
        );

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(actor);
        when(idGenerator.nextId())
                .thenReturn(901L);
        when(ticketNumberGenerator.generate(901L))
                .thenReturn("TKT-P1");
        when(ticketMapper.insert(any()))
                .thenReturn(0);

        CreateTicketRequest request =
                new CreateTicketRequest(
                        "Server unavailable",
                        "Cannot connect to production.",
                        TicketPriority.HIGH
                );

        assertThrows(
                IllegalStateException.class,
                () -> service.create(request)
        );

        verifyNoInteractions(auditLogWriter);
    }
}