package com.nexusagent.ticket.internal;

import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.ticket.api.TicketDetailResponse;
import com.nexusagent.ticket.api.TicketNotFoundException;
import com.nexusagent.ticket.domain.TicketPriority;
import com.nexusagent.ticket.domain.TicketSource;
import com.nexusagent.ticket.domain.TicketStatus;
import com.nexusagent.ticket.internal.persistence.TicketDetailRow;
import com.nexusagent.ticket.internal.persistence.TicketMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultTicketQueryServiceTest {

    @Mock
    private CurrentActorProvider currentActorProvider;

    @Mock
    private TicketMapper ticketMapper;

    private DefaultTicketQueryService service;

    @BeforeEach
    void setUp() {
        service = new DefaultTicketQueryService(
                currentActorProvider,
                ticketMapper
        );
    }

    @Test
    void shouldReturnTicketDetailsWithinCurrentTenant() {
        CurrentActor actor = actor();

        Instant createdAt = Instant.parse(
                "2026-08-08T01:00:00Z"
        );
        Instant updatedAt = Instant.parse(
                "2026-08-08T02:00:00Z"
        );

        TicketDetailRow row = new TicketDetailRow(
                901L,
                202L,
                "TKT-P1",
                "Server unavailable",
                "Cannot connect to production.",
                TicketPriority.HIGH,
                TicketStatus.IN_PROGRESS,
                TicketSource.USER,
                101L,
                404L,
                null,
                1,
                createdAt,
                updatedAt,
                null
        );

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(actor);
        when(ticketMapper
                .findDetailByTenantIdAndTicketNo(
                        202L,
                        "TKT-P1"
                ))
                .thenReturn(Optional.of(row));

        TicketDetailResponse response =
                service.getByTicketNo(
                        " TKT-P1 "
                );

        verify(ticketMapper)
                .findDetailByTenantIdAndTicketNo(
                        202L,
                        "TKT-P1"
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
                        "Server unavailable",
                        response.title()
                ),
                () -> assertEquals(
                        "Cannot connect to production.",
                        response.description()
                ),
                () -> assertEquals(
                        TicketPriority.HIGH,
                        response.priority()
                ),
                () -> assertEquals(
                        TicketStatus.IN_PROGRESS,
                        response.status()
                ),
                () -> assertEquals(
                        TicketSource.USER,
                        response.source()
                ),
                () -> assertEquals(
                        "101",
                        response.requesterUserId()
                ),
                () -> assertEquals(
                        "404",
                        response.assigneeUserId()
                ),
                () -> assertNull(
                        response.createdByAgentId()
                ),
                () -> assertEquals(
                        1,
                        response.version()
                ),
                () -> assertEquals(
                        createdAt,
                        response.createdAt()
                ),
                () -> assertEquals(
                        updatedAt,
                        response.updatedAt()
                ),
                () -> assertNull(
                        response.closedAt()
                )
        );
    }

    @Test
    void shouldThrowNotFoundWhenTicketIsAbsent() {
        CurrentActor actor = actor();

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(actor);
        when(ticketMapper
                .findDetailByTenantIdAndTicketNo(
                        202L,
                        "TKT-MISSING"
                ))
                .thenReturn(Optional.empty());

        TicketNotFoundException exception =
                assertThrows(
                        TicketNotFoundException.class,
                        () -> service.getByTicketNo(
                                "TKT-MISSING"
                        )
                );

        assertEquals(
                "Ticket not found",
                exception.getMessage()
        );

        verify(ticketMapper)
                .findDetailByTenantIdAndTicketNo(
                        202L,
                        "TKT-MISSING"
                );
    }

    @Test
    void shouldRejectBlankTicketNumberBeforeQuery() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(actor());

        assertThrows(
                IllegalArgumentException.class,
                () -> service.getByTicketNo("   ")
        );

        verifyNoInteractions(ticketMapper);
    }

    private static CurrentActor actor() {
        return new CurrentActor(
                101L,
                202L,
                "admin",
                Set.of("ADMIN")
        );
    }
}