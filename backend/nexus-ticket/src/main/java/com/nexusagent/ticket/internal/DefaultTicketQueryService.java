package com.nexusagent.ticket.internal;

import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.ticket.api.TicketDetailResponse;
import com.nexusagent.ticket.api.TicketListQuery;
import com.nexusagent.ticket.api.TicketListResponse;
import com.nexusagent.ticket.api.TicketNotFoundException;
import com.nexusagent.ticket.api.TicketQueryService;
import com.nexusagent.ticket.api.TicketSummaryResponse;
import com.nexusagent.ticket.internal.persistence.TicketDetailRow;
import com.nexusagent.ticket.internal.persistence.TicketListRow;
import com.nexusagent.ticket.internal.persistence.TicketMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class DefaultTicketQueryService
        implements TicketQueryService {

    private static final int MAX_TICKET_NO_LENGTH = 32;

    private final CurrentActorProvider currentActorProvider;
    private final TicketMapper ticketMapper;
    private final TicketCursorCodec ticketCursorCodec;

    public DefaultTicketQueryService(
            CurrentActorProvider currentActorProvider,
            TicketMapper ticketMapper,
            TicketCursorCodec ticketCursorCodec
    ) {
        this.currentActorProvider = currentActorProvider;
        this.ticketMapper = ticketMapper;
        this.ticketCursorCodec = ticketCursorCodec;
    }

    @Override
    @Transactional(readOnly = true)
    public TicketDetailResponse getByTicketNo(
            String ticketNo
    ) {
        CurrentActor actor =
                currentActorProvider.requireCurrentActor();

        String normalizedTicketNo =
                normalizeTicketNo(ticketNo);

        TicketDetailRow row = ticketMapper
                .findDetailByTenantIdAndTicketNo(
                        actor.tenantId(),
                        normalizedTicketNo
                )
                .orElseThrow(
                        TicketNotFoundException::new
                );

        return toDetailResponse(row);
    }

    @Override
    @Transactional(readOnly = true)
    public TicketListResponse list(
            TicketListQuery query
    ) {
        Objects.requireNonNull(
                query,
                "query must not be null"
        );

        CurrentActor actor =
                currentActorProvider.requireCurrentActor();

        TicketPageCursor pageCursor =
                query.cursor() == null
                        ? null
                        : ticketCursorCodec.decode(
                        query.cursor()
                );

        List<TicketListRow> rows =
                ticketMapper.findPage(
                        actor.tenantId(),
                        query.status(),
                        query.priority(),
                        pageCursor == null
                                ? null
                                : pageCursor.createdAt(),
                        pageCursor == null
                                ? null
                                : pageCursor.ticketId(),
                        query.limit() + 1
                );

        Objects.requireNonNull(
                rows,
                "ticketMapper returned null"
        );

        boolean hasMore =
                rows.size() > query.limit();

        int resultSize = Math.min(
                rows.size(),
                query.limit()
        );

        List<TicketListRow> pageRows =
                rows.subList(0, resultSize);

        List<TicketSummaryResponse> items =
                pageRows.stream()
                        .map(row -> toSummaryResponse(
                                row,
                                actor.tenantId()
                        ))
                        .toList();

        String nextCursor = null;

        if (hasMore) {
            TicketListRow lastReturnedRow =
                    pageRows.get(
                            pageRows.size() - 1
                    );

            nextCursor = ticketCursorCodec.encode(
                    new TicketPageCursor(
                            lastReturnedRow.createdAt(),
                            lastReturnedRow.id()
                    )
            );
        }

        return new TicketListResponse(
                items,
                nextCursor,
                hasMore
        );
    }

    private static TicketDetailResponse toDetailResponse(
            TicketDetailRow row
    ) {
        return new TicketDetailResponse(
                Long.toString(row.id()),
                row.ticketNo(),
                row.title(),
                row.description(),
                row.priority(),
                row.status(),
                row.source(),
                Long.toString(row.requesterUserId()),
                nullableId(row.assigneeUserId()),
                nullableId(row.createdByAgentId()),
                row.version(),
                row.createdAt(),
                row.updatedAt(),
                row.closedAt()
        );
    }

    private static TicketSummaryResponse toSummaryResponse(
            TicketListRow row,
            long expectedTenantId
    ) {
        if (row.tenantId() != expectedTenantId) {
            throw new IllegalStateException(
                    "Ticket query returned a row "
                            + "outside the current tenant"
            );
        }

        return new TicketSummaryResponse(
                Long.toString(row.id()),
                row.ticketNo(),
                row.title(),
                row.priority(),
                row.status(),
                row.source(),
                Long.toString(row.requesterUserId()),
                nullableId(row.assigneeUserId()),
                row.version(),
                row.createdAt(),
                row.updatedAt()
        );
    }

    private static String normalizeTicketNo(
            String ticketNo
    ) {
        Objects.requireNonNull(
                ticketNo,
                "ticketNo must not be null"
        );

        String normalized = ticketNo.trim();

        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    "ticketNo must not be blank"
            );
        }

        if (normalized.length() > MAX_TICKET_NO_LENGTH) {
            throw new IllegalArgumentException(
                    "ticketNo must not exceed "
                            + MAX_TICKET_NO_LENGTH
                            + " characters"
            );
        }

        return normalized;
    }

    private static String nullableId(Long id) {
        return id == null
                ? null
                : Long.toString(id);
    }
}