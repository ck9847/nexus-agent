package com.nexusagent.tool.internal;

import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.tool.api.ToolExecutionIdempotencyConflictException;
import com.nexusagent.tool.internal.persistence.ToolExecutionMapper;
import com.nexusagent.tool.internal.persistence.ToolExecutionRegistrationScopeRow;
import com.nexusagent.tool.internal.persistence.ToolExecutionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class ToolExecutionRegistrationTransactions {

    private final ToolExecutionMapper toolExecutionMapper;
    private final ToolInputJsonCodec inputJsonCodec;
    private final AuditLogWriter auditLogWriter;

    public ToolExecutionRegistrationTransactions(
            ToolExecutionMapper toolExecutionMapper,
            ToolInputJsonCodec inputJsonCodec,
            AuditLogWriter auditLogWriter
    ) {
        this.toolExecutionMapper = Objects.requireNonNull(
                toolExecutionMapper
        );
        this.inputJsonCodec = Objects.requireNonNull(
                inputJsonCodec
        );
        this.auditLogWriter = Objects.requireNonNull(
                auditLogWriter
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ToolExecutionRow insert(
            CurrentActor actor,
            ToolExecutionRow candidate
    ) {
        requireValid(actor, candidate);

        ToolExecutionRegistrationScopeRow scope =
                toolExecutionMapper
                        .findRegistrationScopeForUpdate(
                                candidate.conversationId(),
                                actor.tenantId(),
                                actor.userId(),
                                candidate.agentId(),
                                candidate.requestMessageId()
                        )
                        .orElseThrow(
                                ToolExecutionRegistrationScopeException::new
                        );

        if (scope.conversationId()
                != candidate.conversationId()
                || scope.tenantId() != actor.tenantId()
                || scope.userId() != actor.userId()
                || scope.agentId() != candidate.agentId()
                || scope.requestMessageId()
                != candidate.requestMessageId()) {
            throw new IllegalStateException(
                    "Tool execution registration "
                            + "scope mismatch"
            );
        }

        int affectedRows =
                toolExecutionMapper.insert(candidate);

        if (affectedRows != 1) {
            throw new IllegalStateException(
                    "Tool execution insert affected "
                            + affectedRows
                            + " rows"
            );
        }

        auditLogWriter.write(new AuditLogCommand(
                candidate.tenantId(),
                AuditActorType.AGENT,
                candidate.agentId(),
                "TOOL_EXECUTION_REGISTERED",
                "TOOL_EXECUTION",
                candidate.id(),
                candidate.id(),
                AuditResult.SUCCESS,
                null,
                candidate.traceId(),
                null,
                null,
                Map.of(
                        "conversationId",
                        Long.toString(
                                candidate.conversationId()
                        ),
                        "agentId",
                        Long.toString(candidate.agentId()),
                        "requestedByUserId",
                        Long.toString(actor.userId()),
                        "requestMessageId",
                        Long.toString(
                                candidate.requestMessageId()
                        ),
                        "toolCallId",
                        candidate.toolCallId(),
                        "toolName",
                        candidate.toolName(),
                        "status",
                        candidate.status().name(),
                        "approvalRequired",
                        candidate.approvalRequired()
                ),
                null,
                null
        ));

        return candidate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ToolExecutionRow> recover(
            CurrentActor actor,
            ToolExecutionRow candidate
    ) {
        requireValid(actor, candidate);

        Optional<Long> ownedConversation =
                Objects.requireNonNull(
                        toolExecutionMapper
                                .lockOwnedConversationForRecovery(
                                        candidate.conversationId(),
                                        candidate.tenantId(),
                                        actor.userId(),
                                        candidate.agentId()
                                )
                );

        if (ownedConversation.isEmpty()) {
            return Optional.empty();
        }

        if (ownedConversation.get()
                != candidate.conversationId()) {
            throw new IllegalStateException(
                    "Recovered conversation scope mismatch"
            );
        }

        Optional<ToolExecutionRow> byKey =
                Objects.requireNonNull(
                        toolExecutionMapper
                                .findByIdempotencyKeyForUpdate(
                                        candidate.tenantId(),
                                        candidate.idempotencyKey()
                                )
                );

        Optional<ToolExecutionRow> byCall =
                Objects.requireNonNull(
                        toolExecutionMapper
                                .findByConversationAndToolCallIdForUpdate(
                                        candidate.tenantId(),
                                        candidate.conversationId(),
                                        candidate.toolCallId()
                                )
                );

        if (byKey.isEmpty() && byCall.isEmpty()) {
            return Optional.empty();
        }

        if (byKey.isEmpty() || byCall.isEmpty()) {
            throw new ToolExecutionIdempotencyConflictException();
        }

        ToolExecutionRow existing = byKey.get();

        if (existing.id() != byCall.get().id()) {
            throw new ToolExecutionIdempotencyConflictException();
        }

        if (!isIdenticalReplay(existing, candidate)) {
            throw new ToolExecutionIdempotencyConflictException();
        }

        return Optional.of(existing);
    }

    private boolean isIdenticalReplay(
            ToolExecutionRow existing,
            ToolExecutionRow candidate
    ) {
        return existing.tenantId() == candidate.tenantId()
                && existing.conversationId()
                == candidate.conversationId()
                && existing.agentId() == candidate.agentId()
                && Objects.equals(
                existing.requestMessageId(),
                candidate.requestMessageId()
        )
                && Objects.equals(
                existing.toolCallId(),
                candidate.toolCallId()
        )
                && Objects.equals(
                existing.toolName(),
                candidate.toolName()
        )
                && Objects.equals(
                existing.idempotencyKey(),
                candidate.idempotencyKey()
        )
                && existing.approvalRequired()
                == candidate.approvalRequired()
                && inputJsonCodec.decode(existing.inputJson())
                .equals(
                        inputJsonCodec.decode(
                                candidate.inputJson()
                        )
                );
    }

    private static void requireValid(
            CurrentActor actor,
            ToolExecutionRow candidate
    ) {
        Objects.requireNonNull(
                actor,
                "actor must not be null"
        );
        Objects.requireNonNull(
                candidate,
                "candidate must not be null"
        );

        if (candidate.tenantId() != actor.tenantId()) {
            throw new IllegalArgumentException(
                    "candidate tenantId must match "
                            + "actor tenantId"
            );
        }
    }
}
