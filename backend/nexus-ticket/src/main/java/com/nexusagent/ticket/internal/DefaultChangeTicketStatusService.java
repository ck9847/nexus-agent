package com.nexusagent.ticket.internal;

import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.ticket.api.ChangeTicketStatusRequest;
import com.nexusagent.ticket.api.ChangeTicketStatusResponse;
import com.nexusagent.ticket.api.ChangeTicketStatusService;
import com.nexusagent.ticket.api.TicketNotFoundException;
import com.nexusagent.ticket.domain.TicketStatus;
import com.nexusagent.ticket.domain.TicketStatusTransitionPolicy;
import com.nexusagent.ticket.domain.TicketVersionConflictException;
import com.nexusagent.ticket.internal.persistence.TicketMapper;
import com.nexusagent.ticket.internal.persistence.TicketStatusRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

@Service
public class DefaultChangeTicketStatusService
        implements ChangeTicketStatusService {

    private static final int MAX_TICKET_NO_LENGTH = 32;

    private final CurrentActorProvider currentActorProvider;
    private final TicketMapper ticketMapper;
    private final AuditLogWriter auditLogWriter;

    public DefaultChangeTicketStatusService(
            CurrentActorProvider currentActorProvider,
            TicketMapper ticketMapper,
            AuditLogWriter auditLogWriter
    ) {
        this.currentActorProvider = currentActorProvider;
        this.ticketMapper = ticketMapper;
        this.auditLogWriter = auditLogWriter;
    }

    @Override
    @Transactional
    public ChangeTicketStatusResponse changeStatus(
            String ticketNo,
            ChangeTicketStatusRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        String normalizedTicketNo =
                normalizeTicketNo(ticketNo);

        TicketStatus targetStatus =
                Objects.requireNonNull(
                        request.targetStatus(),
                        "targetStatus must not be null"
                );

        Integer expectedVersion =
                Objects.requireNonNull(
                        request.expectedVersion(),
                        "expectedVersion must not be null"
                );

        if (expectedVersion < 0) {
            throw new IllegalArgumentException(
                    "expectedVersion must not be negative"
            );
        }

        CurrentActor actor =
                currentActorProvider.requireCurrentActor();

        TicketStatusRow before = ticketMapper
                .findStatusByTenantIdAndTicketNo(
                        actor.tenantId(),
                        normalizedTicketNo
                )
                .orElseThrow(
                        TicketNotFoundException::new
                );

        if (before.tenantId() != actor.tenantId()) {
            throw new IllegalStateException(
                    "Ticket query returned a row "
                            + "outside the current tenant"
            );
        }

        if (before.version() != expectedVersion) {
            throw new TicketVersionConflictException();
        }

        TicketStatusTransitionPolicy.requireAllowed(
                before.status(),
                targetStatus
        );

        int affectedRows = ticketMapper.updateStatus(
                actor.tenantId(),
                normalizedTicketNo,
                before.status(),
                targetStatus,
                expectedVersion
        );

        if (affectedRows != 1) {
            throw new TicketVersionConflictException();
        }

        TicketStatusRow after = ticketMapper
                .findStatusByTenantIdAndTicketNo(
                        actor.tenantId(),
                        normalizedTicketNo
                )
                .orElseThrow(() -> new IllegalStateException(
                        "Ticket disappeared after status update"
                ));

        verifyUpdatedRow(
                before,
                after,
                actor.tenantId(),
                targetStatus,
                expectedVersion
        );

        auditLogWriter.write(new AuditLogCommand(
                actor.tenantId(),
                AuditActorType.USER,
                actor.userId(),
                "TICKET_STATUS_CHANGED",
                "TICKET",
                before.id(),
                null,
                AuditResult.SUCCESS,
                null,
                null,
                null,
                Map.of(
                        "ticketNo", normalizedTicketNo,
                        "status", before.status().name(),
                        "version", before.version()
                ),
                Map.of(
                        "ticketNo", normalizedTicketNo,
                        "status", after.status().name(),
                        "version", after.version()
                ),
                null,
                null
        ));

        return new ChangeTicketStatusResponse(
                Long.toString(after.id()),
                after.ticketNo(),
                before.status(),
                after.status(),
                after.version(),
                after.closedAt(),
                after.updatedAt()
        );
    }

    private static void verifyUpdatedRow(
            TicketStatusRow before,
            TicketStatusRow after,
            long expectedTenantId,
            TicketStatus targetStatus,
            int expectedVersion
    ) {
        if (after.id() != before.id()
                || after.tenantId() != expectedTenantId
                || after.status() != targetStatus
                || after.version() != expectedVersion + 1) {
            throw new IllegalStateException(
                    "Ticket status update returned "
                            + "an inconsistent row"
            );
        }
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
}