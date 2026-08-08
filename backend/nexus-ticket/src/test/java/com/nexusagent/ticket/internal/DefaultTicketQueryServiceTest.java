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
import com.nexusagent.ticket.api.TicketListQuery;
import com.nexusagent.ticket.api.TicketListResponse;
import com.nexusagent.ticket.internal.persistence.TicketListRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

@ExtendWith(MockitoExtension.class)
class DefaultTicketQueryServiceTest {

    @Mock
    private CurrentActorProvider currentActorProvider;

    @Mock
    private TicketMapper ticketMapper;

    @Mock
    private TicketCursorCodec ticketCursorCodec;

    private DefaultTicketQueryService service;

    @BeforeEach
    void setUp() {
        service = new DefaultTicketQueryService(
                currentActorProvider,
                ticketMapper,
                ticketCursorCodec
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

    @Test
    void shouldReturnFirstPageAndGenerateNextCursor() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(actor());

        Instant firstCreatedAt = Instant.parse(
                "2026-08-08T03:00:00Z"
        );
        Instant secondCreatedAt = Instant.parse(
                "2026-08-08T02:00:00Z"
        );
        Instant thirdCreatedAt = Instant.parse(
                "2026-08-08T01:00:00Z"
        );

        TicketListRow first = listRow(
                903L,
                firstCreatedAt
        );
        TicketListRow second = listRow(
                902L,
                secondCreatedAt
        );
        TicketListRow extra = listRow(
                901L,
                thirdCreatedAt
        );

        when(ticketMapper.findPage(
                202L,
                TicketStatus.OPEN,
                TicketPriority.HIGH,
                null,
                null,
                3
        )).thenReturn(List.of(
                first,
                second,
                extra
        ));

        TicketPageCursor expectedNextCursor =
                new TicketPageCursor(
                        secondCreatedAt,
                        902L
                );

        when(ticketCursorCodec.encode(
                expectedNextCursor
        )).thenReturn("next-cursor");

        TicketListResponse response = service.list(
                new TicketListQuery(
                        TicketStatus.OPEN,
                        TicketPriority.HIGH,
                        2,
                        null
                )
        );

        assertAll(
                () -> assertEquals(
                        2,
                        response.items().size()
                ),
                () -> assertEquals(
                        "903",
                        response.items()
                                .get(0)
                                .ticketId()
                ),
                () -> assertEquals(
                        "902",
                        response.items()
                                .get(1)
                                .ticketId()
                ),
                () -> assertEquals(
                        "next-cursor",
                        response.nextCursor()
                ),
                () -> assertTrue(
                        response.hasMore()
                )
        );

        verify(ticketMapper).findPage(
                202L,
                TicketStatus.OPEN,
                TicketPriority.HIGH,
                null,
                null,
                3
        );

        verify(ticketCursorCodec).encode(
                expectedNextCursor
        );
    }

    @Test
    void shouldUseDecodedCursorForNextPage() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(actor());

        TicketPageCursor decodedCursor =
                new TicketPageCursor(
                        Instant.parse(
                                "2026-08-08T02:00:00Z"
                        ),
                        902L
                );

        when(ticketCursorCodec.decode(
                "current-cursor"
        )).thenReturn(decodedCursor);

        when(ticketMapper.findPage(
                202L,
                null,
                null,
                decodedCursor.createdAt(),
                decodedCursor.ticketId(),
                3
        )).thenReturn(List.of(
                listRow(
                        901L,
                        Instant.parse(
                                "2026-08-08T01:00:00Z"
                        )
                )
        ));

        TicketListResponse response = service.list(
                new TicketListQuery(
                        null,
                        null,
                        2,
                        "current-cursor"
                )
        );

        assertAll(
                () -> assertEquals(
                        1,
                        response.items().size()
                ),
                () -> assertEquals(
                        "901",
                        response.items()
                                .getFirst()
                                .ticketId()
                ),
                () -> assertFalse(
                        response.hasMore()
                ),
                () -> assertNull(
                        response.nextCursor()
                )
        );

        verify(ticketCursorCodec).decode(
                "current-cursor"
        );

        verify(ticketMapper).findPage(
                202L,
                null,
                null,
                decodedCursor.createdAt(),
                decodedCursor.ticketId(),
                3
        );

        verifyNoMoreInteractions(ticketCursorCodec);
    }

    @Test
    void shouldRejectRowOutsideCurrentTenant() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(actor());

        TicketListRow foreignRow =
                new TicketListRow(
                        901L,
                        999L,
                        "TKT-P1",
                        "Foreign tenant ticket",
                        TicketPriority.HIGH,
                        TicketStatus.OPEN,
                        TicketSource.USER,
                        101L,
                        null,
                        0,
                        Instant.parse(
                                "2026-08-08T01:00:00Z"
                        ),
                        Instant.parse(
                                "2026-08-08T01:00:00Z"
                        )
                );

        when(ticketMapper.findPage(
                202L,
                null,
                null,
                null,
                null,
                21
        )).thenReturn(List.of(foreignRow));

        assertThrows(
                IllegalStateException.class,
                () -> service.list(
                        new TicketListQuery(
                                null,
                                null,
                                20,
                                null
                        )
                )
        );
    }

    private static TicketListRow listRow(
            long id,
            Instant createdAt
    ) {
        return new TicketListRow(
                id,
                202L,
                "TKT-" + id,
                "Ticket " + id,
                TicketPriority.HIGH,
                TicketStatus.OPEN,
                TicketSource.USER,
                101L,
                null,
                0,
                createdAt,
                createdAt
        );
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