package com.nexusagent.ticket.internal;

import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.ticket.api.TicketDetailResponse;
import com.nexusagent.ticket.api.TicketNotFoundException;
import com.nexusagent.ticket.api.TicketQueryService;
import com.nexusagent.ticket.internal.persistence.TicketDetailRow;
import com.nexusagent.ticket.internal.persistence.TicketMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class DefaultTicketQueryService
        implements TicketQueryService {

    private static final int MAX_TICKET_NO_LENGTH = 32;

    private final CurrentActorProvider currentActorProvider;
    private final TicketMapper ticketMapper;

    public DefaultTicketQueryService(
            CurrentActorProvider currentActorProvider,
            TicketMapper ticketMapper
    ) {
        this.currentActorProvider = currentActorProvider;
        this.ticketMapper = ticketMapper;
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

        return toResponse(row);
    }

    private static TicketDetailResponse toResponse(
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