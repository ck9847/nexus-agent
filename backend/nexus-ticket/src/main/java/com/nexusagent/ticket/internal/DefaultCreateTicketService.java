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
import com.nexusagent.ticket.api.CreateTicketService;
import com.nexusagent.ticket.domain.TicketPriority;
import com.nexusagent.ticket.domain.TicketSource;
import com.nexusagent.ticket.domain.TicketStatus;
import com.nexusagent.ticket.internal.persistence.TicketMapper;
import com.nexusagent.ticket.internal.persistence.TicketRow;
import com.nexusagent.ticket.spi.TicketNumberGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

@Service
public class DefaultCreateTicketService
        implements CreateTicketService {

    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MAX_DESCRIPTION_LENGTH = 10_000;

    private final CurrentActorProvider currentActorProvider;
    private final IdGenerator idGenerator;
    private final TicketNumberGenerator ticketNumberGenerator;
    private final TicketMapper ticketMapper;
    private final AuditLogWriter auditLogWriter;

    public DefaultCreateTicketService(
            CurrentActorProvider currentActorProvider,
            IdGenerator idGenerator,
            TicketNumberGenerator ticketNumberGenerator,
            TicketMapper ticketMapper,
            AuditLogWriter auditLogWriter
    ) {
        this.currentActorProvider = currentActorProvider;
        this.idGenerator = idGenerator;
        this.ticketNumberGenerator = ticketNumberGenerator;
        this.ticketMapper = ticketMapper;
        this.auditLogWriter = auditLogWriter;
    }

    @Override
    @Transactional
    public CreateTicketResponse create(
            CreateTicketRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        CurrentActor actor =
                currentActorProvider.requireCurrentActor();

        String title = normalizeRequired(
                request.title(),
                "title",
                MAX_TITLE_LENGTH
        );

        String description = normalizeRequired(
                request.description(),
                "description",
                MAX_DESCRIPTION_LENGTH
        );

        TicketPriority priority = Objects.requireNonNull(
                request.priority(),
                "priority must not be null"
        );

        long ticketId = idGenerator.nextId();
        String ticketNo =
                ticketNumberGenerator.generate(ticketId);

        TicketRow row = new TicketRow(
                ticketId,
                actor.tenantId(),
                ticketNo,
                title,
                description,
                priority,
                TicketStatus.OPEN,
                TicketSource.USER,
                actor.userId(),
                null,
                null,
                0
        );

        int affectedRows = ticketMapper.insert(row);

        if (affectedRows != 1) {
            throw new IllegalStateException(
                    "Expected one ticket row to be inserted"
            );
        }

        auditLogWriter.write(new AuditLogCommand(
                actor.tenantId(),
                AuditActorType.USER,
                actor.userId(),
                "TICKET_CREATED",
                "TICKET",
                ticketId,
                null,
                AuditResult.SUCCESS,
                null,
                null,
                null,
                null,
                Map.of(
                        "ticketNo", ticketNo,
                        "title", title,
                        "priority", priority.name(),
                        "status", TicketStatus.OPEN.name(),
                        "source", TicketSource.USER.name()
                ),
                null,
                null
        ));

        return new CreateTicketResponse(
                Long.toString(ticketId),
                ticketNo,
                TicketStatus.OPEN
        );
    }

    private static String normalizeRequired(
            String value,
            String fieldName,
            int maxLength
    ) {
        if (value == null) {
            throw new IllegalArgumentException(
                    fieldName + " must not be null"
            );
        }

        String normalized = value.trim();

        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank"
            );
        }

        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    fieldName + " must not exceed "
                            + maxLength + " characters"
            );
        }

        return normalized;
    }
}