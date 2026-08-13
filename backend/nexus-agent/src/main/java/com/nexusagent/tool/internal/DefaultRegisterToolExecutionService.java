package com.nexusagent.tool.internal;

import com.nexusagent.common.id.IdGenerator;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.conversation.api.ConversationNotFoundException;
import com.nexusagent.tool.api.RegisterToolExecutionCommand;
import com.nexusagent.tool.api.RegisterToolExecutionResult;
import com.nexusagent.tool.api.RegisterToolExecutionService;
import com.nexusagent.tool.domain.ToolExecutionStatus;
import com.nexusagent.tool.internal.persistence.ToolExecutionRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

@Service
public class DefaultRegisterToolExecutionService
        implements RegisterToolExecutionService {

    private final CurrentActorProvider currentActorProvider;
    private final IdGenerator idGenerator;
    private final ToolExecutionIdempotencyKeyFactory keyFactory;
    private final ToolInputJsonCodec inputJsonCodec;
    private final ToolExecutionRegistrationTransactions transactions;
    private final Clock clock;

    public DefaultRegisterToolExecutionService(
            CurrentActorProvider currentActorProvider,
            IdGenerator idGenerator,
            ToolExecutionIdempotencyKeyFactory keyFactory,
            ToolInputJsonCodec inputJsonCodec,
            ToolExecutionRegistrationTransactions transactions,
            Clock clock
    ) {
        this.currentActorProvider = Objects.requireNonNull(
                currentActorProvider
        );
        this.idGenerator = Objects.requireNonNull(idGenerator);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.inputJsonCodec = Objects.requireNonNull(
                inputJsonCodec
        );
        this.transactions = Objects.requireNonNull(
                transactions
        );
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public RegisterToolExecutionResult register(
            RegisterToolExecutionCommand command
    ) {
        Objects.requireNonNull(
                command,
                "command must not be null"
        );

        CurrentActor actor =
                Objects.requireNonNull(
                        currentActorProvider
                                .requireCurrentActor()
                );

        String inputJson =
                inputJsonCodec.encode(command.input());

        String idempotencyKey =
                keyFactory.create(
                        actor.tenantId(),
                        command.conversationId(),
                        command.agentId(),
                        command.requestMessageId(),
                        command.toolCallId(),
                        command.toolName()
                );

        long executionId = idGenerator.nextId();

        if (executionId <= 0) {
            throw new IllegalStateException(
                    "Generated tool execution ID "
                            + "must be positive"
            );
        }

        Instant createdAt =
                Objects.requireNonNull(clock.instant())
                        .truncatedTo(
                                ChronoUnit.MILLIS
                        );

        ToolExecutionStatus initialStatus =
                command.approvalRequired()
                        ? ToolExecutionStatus.WAITING_APPROVAL
                        : ToolExecutionStatus.PENDING;

        ToolExecutionRow candidate =
                new ToolExecutionRow(
                        executionId,
                        actor.tenantId(),
                        command.conversationId(),
                        command.agentId(),
                        command.requestMessageId(),
                        null,
                        command.toolCallId(),
                        command.toolName(),
                        idempotencyKey,
                        inputJson,
                        null,
                        initialStatus,
                        command.approvalRequired(),
                        null,
                        null,
                        null,
                        null,
                        command.traceId(),
                        null,
                        null,
                        null,
                        createdAt,
                        createdAt
                );

        try {
            ToolExecutionRow inserted =
                    transactions.insert(
                            actor,
                            candidate
                    );

            return toResult(inserted, true);

        } catch (DuplicateKeyException duplicate) {
            return recoverDuplicate(
                    actor,
                    candidate,
                    duplicate
            );

        } catch (ToolExecutionRegistrationScopeException scope) {
            return recoverHistorical(
                    actor,
                    candidate
            );
        }
    }

    private RegisterToolExecutionResult recoverDuplicate(
            CurrentActor actor,
            ToolExecutionRow candidate,
            DuplicateKeyException duplicate
    ) {
        Optional<ToolExecutionRow> recovered =
                Objects.requireNonNull(
                        transactions.recover(
                                actor,
                                candidate
                        )
                );

        ToolExecutionRow existing =
                recovered.orElseThrow(() ->
                        new IllegalStateException(
                                "Duplicate tool execution "
                                        + "could not be recovered",
                                duplicate
                        )
                );

        return toResult(existing, false);
    }

    private RegisterToolExecutionResult recoverHistorical(
            CurrentActor actor,
            ToolExecutionRow candidate
    ) {
        Optional<ToolExecutionRow> recovered =
                Objects.requireNonNull(
                        transactions.recover(
                                actor,
                                candidate
                        )
                );

        ToolExecutionRow existing =
                recovered.orElseThrow(
                        ConversationNotFoundException::new
                );

        return toResult(existing, false);
    }

    private static RegisterToolExecutionResult toResult(
            ToolExecutionRow row,
            boolean newlyCreated
    ) {
        return new RegisterToolExecutionResult(
                row.id(),
                row.idempotencyKey(),
                row.status(),
                newlyCreated,
                row.createdAt()
        );
    }
}
