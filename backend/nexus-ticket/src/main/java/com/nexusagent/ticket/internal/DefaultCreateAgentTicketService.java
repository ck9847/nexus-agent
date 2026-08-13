package com.nexusagent.ticket.internal;

import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.common.id.IdGenerator;
import com.nexusagent.ticket.api.CreateAgentTicketCommand;
import com.nexusagent.ticket.api.CreateAgentTicketService;
import com.nexusagent.ticket.api.CreateTicketResponse;
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
@Transactional
public class DefaultCreateAgentTicketService
        implements CreateAgentTicketService {

    private final IdGenerator idGenerator;
    private final TicketNumberGenerator ticketNumberGenerator;
    private final TicketMapper ticketMapper;
    private final AuditLogWriter auditLogWriter;

    public DefaultCreateAgentTicketService(
            IdGenerator idGenerator,
            TicketNumberGenerator ticketNumberGenerator,
            TicketMapper ticketMapper,
            AuditLogWriter auditLogWriter
    ) {
        this.idGenerator = Objects.requireNonNull(
                idGenerator
        );
        this.ticketNumberGenerator = Objects.requireNonNull(
                ticketNumberGenerator
        );
        this.ticketMapper = Objects.requireNonNull(
                ticketMapper
        );
        this.auditLogWriter = Objects.requireNonNull(
                auditLogWriter
        );
    }

    @Override
    public CreateTicketResponse create(
            CreateAgentTicketCommand command
    ) {
        Objects.requireNonNull(
                command,
                "command must not be null"
        );

        NormalizedTicketCreation input =
                TicketCreationNormalizer.normalize(
                        command.title(),
                        command.description(),
                        command.priority()
                );

        long ticketId = idGenerator.nextId();
        String ticketNo =
                ticketNumberGenerator.generate(ticketId);

        TicketRow row = new TicketRow(
                ticketId,
                command.tenantId(),
                ticketNo,
                input.title(),
                input.description(),
                input.priority(),
                TicketStatus.OPEN,
                TicketSource.AGENT,
                command.requesterUserId(),
                null,
                command.createdByAgentId(),
                0
        );

        int affectedRows = ticketMapper.insert(row);

        if (affectedRows != 1) {
            throw new IllegalStateException(
                    "Expected one ticket row to be inserted"
            );
        }

        auditLogWriter.write(new AuditLogCommand(
                command.tenantId(),
                AuditActorType.AGENT,
                command.createdByAgentId(),
                "TICKET_CREATED",
                "TICKET",
                ticketId,
                command.toolExecutionId(),
                AuditResult.SUCCESS,
                null,
                null,
                null,
                null,
                Map.of(
                        "ticketNo", ticketNo,
                        "priority", input.priority().name(),
                        "status", TicketStatus.OPEN.name(),
                        "source", TicketSource.AGENT.name(),
                        "requesterUserId",
                        Long.toString(
                                command.requesterUserId()
                        ),
                        "createdByAgentId",
                        Long.toString(
                                command.createdByAgentId()
                        )
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
}
