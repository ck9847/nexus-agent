package com.nexusagent.ticket.internal;

import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.ticket.api.ChangeTicketStatusRequest;
import com.nexusagent.ticket.api.ChangeTicketStatusResponse;
import com.nexusagent.ticket.api.TicketNotFoundException;
import com.nexusagent.ticket.domain.InvalidTicketStatusTransitionException;
import com.nexusagent.ticket.domain.TicketStatus;
import com.nexusagent.ticket.domain.TicketVersionConflictException;
import com.nexusagent.ticket.internal.persistence.TicketMapper;
import com.nexusagent.ticket.internal.persistence.TicketStatusRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultChangeTicketStatusServiceTest {

    private static final long USER_ID = 101L;
    private static final long TENANT_ID = 202L;
    private static final long TICKET_ID = 901L;
    private static final String TICKET_NO = "TKT-P1";

    @Mock
    private CurrentActorProvider currentActorProvider;

    @Mock
    private TicketMapper ticketMapper;

    @Mock
    private AuditLogWriter auditLogWriter;

    private DefaultChangeTicketStatusService service;

    @BeforeEach
    void setUp() {
        service = new DefaultChangeTicketStatusService(
                currentActorProvider,
                ticketMapper,
                auditLogWriter
        );
    }

    @Test
    void shouldChangeOpenTicketToInProgressAndWriteAudit() {
        Instant beforeUpdatedAt =
                Instant.parse("2026-08-09T01:00:00Z");

        Instant afterUpdatedAt =
                Instant.parse("2026-08-09T01:01:00Z");

        TicketStatusRow before = row(
                TicketStatus.OPEN,
                0,
                null,
                beforeUpdatedAt
        );

        TicketStatusRow after = row(
                TicketStatus.IN_PROGRESS,
                1,
                null,
                afterUpdatedAt
        );

        stubActor();

        when(ticketMapper.findStatusByTenantIdAndTicketNo(
                TENANT_ID,
                TICKET_NO
        )).thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));

        when(ticketMapper.updateStatus(
                TENANT_ID,
                TICKET_NO,
                TicketStatus.OPEN,
                TicketStatus.IN_PROGRESS,
                0
        )).thenReturn(1);

        ChangeTicketStatusResponse response =
                service.changeStatus(
                        " " + TICKET_NO + " ",
                        new ChangeTicketStatusRequest(
                                TicketStatus.IN_PROGRESS,
                                0
                        )
                );

        ArgumentCaptor<AuditLogCommand> auditCaptor =
                ArgumentCaptor.forClass(
                        AuditLogCommand.class
                );

        verify(auditLogWriter).write(
                auditCaptor.capture()
        );

        AuditLogCommand audit = auditCaptor.getValue();

        assertAll(
                () -> assertEquals(
                        Long.toString(TICKET_ID),
                        response.ticketId()
                ),
                () -> assertEquals(
                        TICKET_NO,
                        response.ticketNo()
                ),
                () -> assertEquals(
                        TicketStatus.OPEN,
                        response.previousStatus()
                ),
                () -> assertEquals(
                        TicketStatus.IN_PROGRESS,
                        response.currentStatus()
                ),
                () -> assertEquals(
                        1,
                        response.version()
                ),
                () -> assertNull(
                        response.closedAt()
                ),
                () -> assertEquals(
                        afterUpdatedAt,
                        response.updatedAt()
                )
        );

        assertAll(
                () -> assertEquals(
                        TENANT_ID,
                        audit.tenantId()
                ),
                () -> assertEquals(
                        AuditActorType.USER,
                        audit.actorType()
                ),
                () -> assertEquals(
                        USER_ID,
                        audit.actorId()
                ),
                () -> assertEquals(
                        "TICKET_STATUS_CHANGED",
                        audit.action()
                ),
                () -> assertEquals(
                        "TICKET",
                        audit.resourceType()
                ),
                () -> assertEquals(
                        TICKET_ID,
                        audit.resourceId()
                ),
                () -> assertEquals(
                        AuditResult.SUCCESS,
                        audit.result()
                ),
                () -> assertEquals(
                        Map.of(
                                "ticketNo", TICKET_NO,
                                "status", "OPEN",
                                "version", 0
                        ),
                        audit.beforeData()
                ),
                () -> assertEquals(
                        Map.of(
                                "ticketNo", TICKET_NO,
                                "status", "IN_PROGRESS",
                                "version", 1
                        ),
                        audit.afterData()
                )
        );
    }

    @Test
    void shouldCloseResolvedTicketAndReturnClosedAt() {
        Instant beforeUpdatedAt =
                Instant.parse("2026-08-09T02:00:00Z");

        Instant closedAt =
                Instant.parse("2026-08-09T02:01:00Z");

        TicketStatusRow before = row(
                TicketStatus.RESOLVED,
                3,
                null,
                beforeUpdatedAt
        );

        TicketStatusRow after = row(
                TicketStatus.CLOSED,
                4,
                closedAt,
                closedAt
        );

        stubActor();

        when(ticketMapper.findStatusByTenantIdAndTicketNo(
                TENANT_ID,
                TICKET_NO
        )).thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));

        when(ticketMapper.updateStatus(
                TENANT_ID,
                TICKET_NO,
                TicketStatus.RESOLVED,
                TicketStatus.CLOSED,
                3
        )).thenReturn(1);

        ChangeTicketStatusResponse response =
                service.changeStatus(
                        TICKET_NO,
                        new ChangeTicketStatusRequest(
                                TicketStatus.CLOSED,
                                3
                        )
                );

        assertAll(
                () -> assertEquals(
                        TicketStatus.RESOLVED,
                        response.previousStatus()
                ),
                () -> assertEquals(
                        TicketStatus.CLOSED,
                        response.currentStatus()
                ),
                () -> assertEquals(
                        4,
                        response.version()
                ),
                () -> assertEquals(
                        closedAt,
                        response.closedAt()
                ),
                () -> assertEquals(
                        closedAt,
                        response.updatedAt()
                )
        );

        verify(auditLogWriter).write(
                any(AuditLogCommand.class)
        );
    }

    @Test
    void shouldThrowNotFoundWhenTicketDoesNotExist() {
        stubActor();

        when(ticketMapper.findStatusByTenantIdAndTicketNo(
                TENANT_ID,
                TICKET_NO
        )).thenReturn(Optional.empty());

        assertThrows(
                TicketNotFoundException.class,
                () -> service.changeStatus(
                        TICKET_NO,
                        request(
                                TicketStatus.IN_PROGRESS,
                                0
                        )
                )
        );

        verifyNoMutation();
    }

    @Test
    void shouldRejectStaleExpectedVersion() {
        stubActor();

        when(ticketMapper.findStatusByTenantIdAndTicketNo(
                TENANT_ID,
                TICKET_NO
        )).thenReturn(Optional.of(row(
                TicketStatus.OPEN,
                2,
                null,
                Instant.parse(
                        "2026-08-09T03:00:00Z"
                )
        )));

        assertThrows(
                TicketVersionConflictException.class,
                () -> service.changeStatus(
                        TICKET_NO,
                        request(
                                TicketStatus.IN_PROGRESS,
                                1
                        )
                )
        );

        verifyNoMutation();
    }

    @Test
    void shouldRejectInvalidStatusTransition() {
        stubActor();

        when(ticketMapper.findStatusByTenantIdAndTicketNo(
                TENANT_ID,
                TICKET_NO
        )).thenReturn(Optional.of(row(
                TicketStatus.OPEN,
                0,
                null,
                Instant.parse(
                        "2026-08-09T04:00:00Z"
                )
        )));

        InvalidTicketStatusTransitionException exception =
                assertThrows(
                        InvalidTicketStatusTransitionException.class,
                        () -> service.changeStatus(
                                TICKET_NO,
                                request(
                                        TicketStatus.CLOSED,
                                        0
                                )
                        )
                );

        assertAll(
                () -> assertEquals(
                        TicketStatus.OPEN,
                        exception.currentStatus()
                ),
                () -> assertEquals(
                        TicketStatus.CLOSED,
                        exception.targetStatus()
                )
        );

        verifyNoMutation();
    }

    @Test
    void shouldTreatConcurrentUpdateAsVersionConflict() {
        stubActor();

        when(ticketMapper.findStatusByTenantIdAndTicketNo(
                TENANT_ID,
                TICKET_NO
        )).thenReturn(Optional.of(row(
                TicketStatus.OPEN,
                0,
                null,
                Instant.parse(
                        "2026-08-09T05:00:00Z"
                )
        )));

        when(ticketMapper.updateStatus(
                TENANT_ID,
                TICKET_NO,
                TicketStatus.OPEN,
                TicketStatus.IN_PROGRESS,
                0
        )).thenReturn(0);

        assertThrows(
                TicketVersionConflictException.class,
                () -> service.changeStatus(
                        TICKET_NO,
                        request(
                                TicketStatus.IN_PROGRESS,
                                0
                        )
                )
        );

        verify(ticketMapper).updateStatus(
                TENANT_ID,
                TICKET_NO,
                TicketStatus.OPEN,
                TicketStatus.IN_PROGRESS,
                0
        );

        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldRejectInconsistentRowAfterUpdate() {
        stubActor();

        TicketStatusRow before = row(
                TicketStatus.OPEN,
                0,
                null,
                Instant.parse(
                        "2026-08-09T06:00:00Z"
                )
        );

        TicketStatusRow inconsistentAfter = row(
                TicketStatus.IN_PROGRESS,
                9,
                null,
                Instant.parse(
                        "2026-08-09T06:01:00Z"
                )
        );

        when(ticketMapper.findStatusByTenantIdAndTicketNo(
                TENANT_ID,
                TICKET_NO
        ))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(inconsistentAfter));

        when(ticketMapper.updateStatus(
                TENANT_ID,
                TICKET_NO,
                TicketStatus.OPEN,
                TicketStatus.IN_PROGRESS,
                0
        )).thenReturn(1);

        assertThrows(
                IllegalStateException.class,
                () -> service.changeStatus(
                        TICKET_NO,
                        request(
                                TicketStatus.IN_PROGRESS,
                                0
                        )
                )
        );

        verifyNoInteractions(auditLogWriter);
    }

    private void stubActor() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(new CurrentActor(
                        USER_ID,
                        TENANT_ID,
                        "admin",
                        Set.of("ADMIN")
                ));
    }

    private void verifyNoMutation() {
        verify(ticketMapper, never()).updateStatus(
                anyLong(),
                anyString(),
                any(TicketStatus.class),
                any(TicketStatus.class),
                anyInt()
        );

        verifyNoInteractions(auditLogWriter);
    }

    private static ChangeTicketStatusRequest request(
            TicketStatus targetStatus,
            int expectedVersion
    ) {
        return new ChangeTicketStatusRequest(
                targetStatus,
                expectedVersion
        );
    }

    private static TicketStatusRow row(
            TicketStatus status,
            int version,
            Instant closedAt,
            Instant updatedAt
    ) {
        return new TicketStatusRow(
                TICKET_ID,
                TENANT_ID,
                TICKET_NO,
                status,
                version,
                closedAt,
                updatedAt
        );
    }
}