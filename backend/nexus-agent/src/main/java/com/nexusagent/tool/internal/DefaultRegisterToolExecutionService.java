package com.nexusagent.tool.internal;

import com.nexusagent.common.id.IdGenerator;
import com.nexusagent.common.observability.RequestCorrelation;
import com.nexusagent.common.observability.RequestCorrelationProvider;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.conversation.api.ConversationNotFoundException;
import com.nexusagent.tool.api.RegisterToolExecutionCommand;
import com.nexusagent.tool.api.RegisterToolExecutionResult;
import com.nexusagent.tool.api.RegisterToolExecutionService;
import com.nexusagent.tool.api.ToolExecutionIdempotencyConflictException;
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
    private final RequestCorrelationProvider correlationProvider;
    private final Clock clock;
    private final ToolExecutionMetrics metrics;

    public DefaultRegisterToolExecutionService(
            CurrentActorProvider currentActorProvider,
            IdGenerator idGenerator,
            ToolExecutionIdempotencyKeyFactory keyFactory,
            ToolInputJsonCodec inputJsonCodec,
            ToolExecutionRegistrationTransactions transactions,
            RequestCorrelationProvider correlationProvider,
            Clock clock,
            ToolExecutionMetrics metrics
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
        this.correlationProvider = Objects.requireNonNull(
                correlationProvider
        );
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
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
                        resolveTraceId(command),
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

            // count 内部吞掉所有指标异常：insert 已提交成功时，
            // 绝不因指标异常向调用方抛错或触发 recover 重放。
            metrics.count(
                    ToolExecutionMetrics.OUTCOME_REGISTERED,
                    false
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

    /**
     * 解析 traceId：命令显式值优先，缺失时回落当前请求关联。
     *
     * <p>无 HTTP 上下文（后台任务）时
     * {@link java.util.Optional#empty()} 允许写入 null；traceId
     * 不参与幂等重放判等——已持久化 execution 的原始 traceId
     * 不会被后续重试覆盖。Provider 实现自身的真实故障作为异常
     * 向外传播，绝不会被误判为"没有请求上下文"。
     */
    private String resolveTraceId(
            RegisterToolExecutionCommand command
    ) {
        if (command.traceId() != null) {
            return command.traceId();
        }

        RequestCorrelation correlation =
                correlationProvider
                        .currentCorrelation()
                        .orElse(null);

        return correlation != null
                ? correlation.traceId()
                : null;
    }

    private RegisterToolExecutionResult recoverDuplicate(
            CurrentActor actor,
            ToolExecutionRow candidate,
            DuplicateKeyException duplicate
    ) {
        Optional<ToolExecutionRow> recovered;

        try {
            recovered = Objects.requireNonNull(
                    transactions.recover(
                            actor,
                            candidate
                    )
            );
        } catch (ToolExecutionIdempotencyConflictException
                conflict) {
            metrics.count(
                    ToolExecutionMetrics.OUTCOME_CONFLICT,
                    true
            );
            throw conflict;
        }

        ToolExecutionRow existing =
                recovered.orElseThrow(() ->
                        new IllegalStateException(
                                "Duplicate tool execution "
                                        + "could not be recovered",
                                duplicate
                        )
                );

        // 幂等重放绝不统计成第二次业务成功。
        // count 内部吞掉所有指标异常：重放结果绝不因指标异常改变。
        metrics.count(
                ToolExecutionMetrics.OUTCOME_REPLAYED,
                true
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

        // 幂等重放绝不统计成第二次业务成功。
        metrics.count(
                ToolExecutionMetrics.OUTCOME_REPLAYED,
                true
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
