package com.nexusagent.agent.internal;

import com.nexusagent.agent.api.AgentAdministrationForbiddenException;
import com.nexusagent.agent.api.AgentNotFoundException;
import com.nexusagent.agent.api.ChangeAgentStatusRequest;
import com.nexusagent.agent.api.ChangeAgentStatusResponse;
import com.nexusagent.agent.api.ChangeAgentStatusService;
import com.nexusagent.agent.domain.AgentStatus;
import com.nexusagent.agent.domain.AgentStatusTransitionPolicy;
import com.nexusagent.agent.domain.AgentVersionConflictException;
import com.nexusagent.agent.internal.persistence.AgentMapper;
import com.nexusagent.agent.internal.persistence.AgentStatusRow;
import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

@Service
public class DefaultChangeAgentStatusService
        implements ChangeAgentStatusService {

    private static final String ADMIN_ROLE = "ADMIN";

    private final CurrentActorProvider currentActorProvider;
    private final AgentMapper agentMapper;
    private final AuditLogWriter auditLogWriter;

    public DefaultChangeAgentStatusService(
            CurrentActorProvider currentActorProvider,
            AgentMapper agentMapper,
            AuditLogWriter auditLogWriter
    ) {
        this.currentActorProvider = currentActorProvider;
        this.agentMapper = agentMapper;
        this.auditLogWriter = auditLogWriter;
    }

    @Override
    @Transactional
    public ChangeAgentStatusResponse changeStatus(
            String agentCode,
            ChangeAgentStatusRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        CurrentActor actor =
                currentActorProvider.requireCurrentActor();

        requireAdministrator(actor);

        String code =
                AgentCodeNormalizer.normalize(
                        agentCode
                );

        AgentStatus targetStatus =
                requireTargetStatus(
                        request.targetStatus()
                );

        int expectedVersion =
                requireExpectedVersion(
                        request.expectedVersion()
                );

        AgentStatusRow before = agentMapper
                .findStatusByTenantIdAndCode(
                        actor.tenantId(),
                        code
                )
                .orElseThrow(
                        AgentNotFoundException::new
                );

        verifyBeforeRow(
                before,
                actor.tenantId(),
                code
        );

        if (before.version() != expectedVersion) {
            throw new AgentVersionConflictException();
        }

        AgentStatusTransitionPolicy.requireAllowed(
                before.status(),
                targetStatus
        );

        int affectedRows = agentMapper.updateStatus(
                actor.tenantId(),
                code,
                before.status(),
                targetStatus,
                expectedVersion
        );

        if (affectedRows != 1) {
            throw new AgentVersionConflictException();
        }

        AgentStatusRow after = agentMapper
                .findStatusByTenantIdAndCode(
                        actor.tenantId(),
                        code
                )
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Agent disappeared "
                                        + "after status update"
                        )
                );

        verifyAfterRow(
                before,
                after,
                actor.tenantId(),
                code,
                targetStatus
        );

        auditLogWriter.write(new AuditLogCommand(
                actor.tenantId(),
                AuditActorType.USER,
                actor.userId(),
                "AGENT_STATUS_CHANGED",
                "AGENT",
                before.id(),
                null,
                AuditResult.SUCCESS,
                null,
                null,
                null,
                Map.of(
                        "code", code,
                        "status",
                        before.status().name(),
                        "version",
                        before.version()
                ),
                Map.of(
                        "code", code,
                        "status",
                        after.status().name(),
                        "version",
                        after.version()
                ),
                null,
                null
        ));

        return new ChangeAgentStatusResponse(
                Long.toString(after.id()),
                after.code(),
                before.status(),
                after.status(),
                after.version(),
                after.updatedAt()
        );
    }

    private static void requireAdministrator(
            CurrentActor actor
    ) {
        if (!actor.hasRole(ADMIN_ROLE)) {
            throw new AgentAdministrationForbiddenException();
        }
    }

    private static AgentStatus requireTargetStatus(
            AgentStatus targetStatus
    ) {
        if (targetStatus == null) {
            throw new IllegalArgumentException(
                    "targetStatus must not be null"
            );
        }

        return targetStatus;
    }

    private static int requireExpectedVersion(
            Integer expectedVersion
    ) {
        if (expectedVersion == null) {
            throw new IllegalArgumentException(
                    "expectedVersion must not be null"
            );
        }

        if (expectedVersion < 0) {
            throw new IllegalArgumentException(
                    "expectedVersion must not be negative"
            );
        }

        return expectedVersion;
    }

    private static void verifyBeforeRow(
            AgentStatusRow before,
            long expectedTenantId,
            String expectedCode
    ) {
        if (before.id() <= 0
                || before.tenantId()
                != expectedTenantId
                || !expectedCode.equals(
                before.code()
        )
                || before.status() == null
                || before.version() < 0
                || before.updatedAt() == null) {
            throw new IllegalStateException(
                    "Agent status query returned "
                            + "an inconsistent row"
            );
        }
    }

    private static void verifyAfterRow(
            AgentStatusRow before,
            AgentStatusRow after,
            long expectedTenantId,
            String expectedCode,
            AgentStatus targetStatus
    ) {
        if (after.id() != before.id()
                || after.tenantId()
                != expectedTenantId
                || !expectedCode.equals(
                after.code()
        )
                || after.status() != targetStatus
                || after.version()
                != before.version() + 1
                || after.updatedAt() == null
                || after.updatedAt().isBefore(
                before.updatedAt()
        )) {
            throw new IllegalStateException(
                    "Agent status update returned "
                            + "an inconsistent row"
            );
        }
    }
}