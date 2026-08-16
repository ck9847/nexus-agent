package com.nexusagent.tool.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.common.id.IdGenerator;
import com.nexusagent.common.observability.RequestCorrelation;
import com.nexusagent.common.observability.RequestCorrelationProvider;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.conversation.api.ConversationNotFoundException;
import com.nexusagent.testing.ThrowingMeterRegistry;
import com.nexusagent.tool.api.RegisterToolExecutionCommand;
import com.nexusagent.tool.api.RegisterToolExecutionResult;
import com.nexusagent.tool.domain.ToolExecutionStatus;
import com.nexusagent.tool.internal.persistence.ToolExecutionRow;
import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultRegisterToolExecutionServiceTest {

    private static final long USER_ID = 101L;
    private static final long TENANT_ID = 202L;
    private static final long CONVERSATION_ID = 901L;
    private static final long AGENT_ID = 500L;
    private static final long REQUEST_MESSAGE_ID = 1001L;
    private static final long EXECUTION_ID = 7001L;

    private static final String IDEMPOTENCY_KEY =
            "tool:v1:" + "a".repeat(64);

    private static final String CANONICAL_INPUT_JSON =
            "{\"query\":\"latest\"}";

    private static final Instant RAW_NOW =
            Instant.parse(
                    "2026-08-13T10:15:30.123456Z"
            );

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-13T10:15:30.123Z"
            );

    private static final Instant OLD_NOW =
            Instant.parse(
                    "2026-08-13T09:00:00Z"
            );

    private static final CurrentActor ACTOR =
            new CurrentActor(
                    USER_ID,
                    TENANT_ID,
                    "member",
                    Set.of("MEMBER")
            );

    private static final RequestCorrelation CORRELATION =
            new RequestCorrelation(
                    "ctx-req-1",
                    "ctx-trace-1",
                    "10.0.0.9"
            );

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper();

    @Mock
    private CurrentActorProvider currentActorProvider;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private ToolExecutionIdempotencyKeyFactory keyFactory;

    @Mock
    private ToolInputJsonCodec inputJsonCodec;

    @Mock
    private ToolExecutionRegistrationTransactions transactions;

    @Mock
    private RequestCorrelationProvider correlationProvider;

    @Mock
    private Clock clock;

    private final ThrowingMeterRegistry meterRegistry =
            new ThrowingMeterRegistry();

    private DefaultRegisterToolExecutionService service;

    @BeforeEach
    void setUp() {
        service = new DefaultRegisterToolExecutionService(
                currentActorProvider,
                idGenerator,
                keyFactory,
                inputJsonCodec,
                transactions,
                correlationProvider,
                clock,
                new ToolExecutionMetrics(meterRegistry)
        );
    }

    @Test
    void shouldRegisterNewExecution() {
        stubActorAndInputs();
        stubIdAndClock();

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenAnswer(invocation ->
                invocation.getArgument(1)
        );

        RegisterToolExecutionResult result =
                service.register(command(false));

        assertEquals(
                EXECUTION_ID,
                result.toolExecutionId()
        );
        assertEquals(
                IDEMPOTENCY_KEY,
                result.idempotencyKey()
        );
        assertEquals(
                ToolExecutionStatus.PENDING,
                result.status()
        );
        assertTrue(result.newlyCreated());
        assertEquals(NOW, result.createdAt());

        verify(transactions).insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        );
    }

    @Test
    void shouldReturnNewlyCreatedWhenMetricsFailAfterInsert() {
        // insert 已提交成功；指标异常必须被吞掉：
        // 仍返回 newlyCreated=true，不调用 recover，不重复注册。
        meterRegistry.throwOnCounterCreation();

        stubActorAndInputs();
        stubIdAndClock();

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenAnswer(invocation ->
                invocation.getArgument(1)
        );

        RegisterToolExecutionResult result =
                service.register(command(false));

        assertTrue(result.newlyCreated());
        assertEquals(
                EXECUTION_ID,
                result.toolExecutionId()
        );
        assertEquals(
                ToolExecutionStatus.PENDING,
                result.status()
        );
        assertEquals(NOW, result.createdAt());

        verify(transactions).insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        );
        verify(transactions, never()).recover(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        );
    }

    @Test
    void shouldReplayExistingExecutionWhenMetricsFail() {
        // 幂等重放结果绝不能因指标异常改变或抛错：
        // 仍返回原 execution，newlyCreated=false。
        meterRegistry.throwOnCounterCreation();

        stubActorAndInputs();
        stubIdAndClock();

        ToolExecutionRow existing = row(
                9000L,
                ToolExecutionStatus.SUCCEEDED,
                "trace-1",
                OLD_NOW
        );

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenThrow(new DuplicateKeyException("duplicate"));

        when(transactions.recover(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenReturn(Optional.of(existing));

        RegisterToolExecutionResult result =
                service.register(command(false));

        assertEquals(9000L, result.toolExecutionId());
        assertEquals(
                ToolExecutionStatus.SUCCEEDED,
                result.status()
        );
        assertFalse(result.newlyCreated());
        assertEquals(OLD_NOW, result.createdAt());

        verify(transactions).insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        );
    }

    @Test
    void shouldCreatePendingWhenApprovalNotRequired() {
        stubActorAndInputs();
        stubIdAndClock();

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenAnswer(invocation ->
                invocation.getArgument(1)
        );

        service.register(command(false));

        ArgumentCaptor<ToolExecutionRow> captor =
                ArgumentCaptor.forClass(ToolExecutionRow.class);

        verify(transactions).insert(
                any(CurrentActor.class),
                captor.capture()
        );

        assertEquals(
                ToolExecutionStatus.PENDING,
                captor.getValue().status()
        );
    }

    @Test
    void shouldCreateWaitingApprovalWhenApprovalRequired() {
        stubActorAndInputs();
        stubIdAndClock();

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenAnswer(invocation ->
                invocation.getArgument(1)
        );

        service.register(command(true));

        ArgumentCaptor<ToolExecutionRow> captor =
                ArgumentCaptor.forClass(ToolExecutionRow.class);

        verify(transactions).insert(
                any(CurrentActor.class),
                captor.capture()
        );

        assertEquals(
                ToolExecutionStatus.WAITING_APPROVAL,
                captor.getValue().status()
        );
    }

    @Test
    void shouldBuildKeyFromActorTenant() {
        stubActorAndInputs();
        stubIdAndClock();

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenAnswer(invocation ->
                invocation.getArgument(1)
        );

        service.register(command(false));

        verify(keyFactory).create(
                TENANT_ID,
                CONVERSATION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                "call-1",
                "search"
        );
    }

    @Test
    void shouldCanonicalEncodeInputBeforeInserting() {
        stubActorAndInputs();
        stubIdAndClock();

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenAnswer(invocation ->
                invocation.getArgument(1)
        );

        RegisterToolExecutionCommand cmd =
                command(false);

        service.register(cmd);

        ArgumentCaptor<JsonNode> jsonCaptor =
                ArgumentCaptor.forClass(JsonNode.class);

        ArgumentCaptor<ToolExecutionRow> rowCaptor =
                ArgumentCaptor.forClass(ToolExecutionRow.class);

        InOrder inOrder = inOrder(
                inputJsonCodec,
                transactions
        );

        inOrder.verify(inputJsonCodec).encode(
                jsonCaptor.capture()
        );
        inOrder.verify(transactions).insert(
                any(CurrentActor.class),
                rowCaptor.capture()
        );

        assertEquals(cmd.input(), jsonCaptor.getValue());
        assertEquals(
                CANONICAL_INPUT_JSON,
                rowCaptor.getValue().inputJson()
        );
    }

    @Test
    void shouldTruncateCreatedAtToMillis() {
        stubActorAndInputs();
        stubIdAndClock();

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenAnswer(invocation ->
                invocation.getArgument(1)
        );

        service.register(command(false));

        ArgumentCaptor<ToolExecutionRow> captor =
                ArgumentCaptor.forClass(ToolExecutionRow.class);

        verify(transactions).insert(
                any(CurrentActor.class),
                captor.capture()
        );

        assertEquals(NOW, captor.getValue().createdAt());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void shouldRejectNonPositiveGeneratedId(
            long generatedId
    ) {
        stubActorAndInputs();

        when(idGenerator.nextId())
                .thenReturn(generatedId);

        assertThrows(
                IllegalStateException.class,
                () -> service.register(command(false))
        );

        verifyNoInteractions(transactions);
    }

    @Test
    void shouldRecoverExistingOnDuplicateKey() {
        stubActorAndInputs();
        stubIdAndClock();

        ToolExecutionRow existing = row(
                9000L,
                ToolExecutionStatus.SUCCEEDED,
                "trace-1",
                OLD_NOW
        );

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenThrow(new DuplicateKeyException("duplicate"));

        when(transactions.recover(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenReturn(Optional.of(existing));

        RegisterToolExecutionResult result =
                service.register(command(false));

        assertEquals(9000L, result.toolExecutionId());
        assertEquals(
                ToolExecutionStatus.SUCCEEDED,
                result.status()
        );
        assertFalse(result.newlyCreated());
        assertEquals(OLD_NOW, result.createdAt());
    }

    @Test
    void shouldThrowWhenDuplicateCannotBeRecovered() {
        stubActorAndInputs();
        stubIdAndClock();

        DuplicateKeyException duplicate =
                new DuplicateKeyException("duplicate");

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenThrow(duplicate);

        when(transactions.recover(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenReturn(Optional.empty());

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.register(
                                command(false)
                        )
                );

        assertSame(duplicate, exception.getCause());
    }

    @Test
    void shouldRecoverHistoricallyOnScopeFailure() {
        stubActorAndInputs();
        stubIdAndClock();

        ToolExecutionRow existing = row(
                9000L,
                ToolExecutionStatus.SUCCEEDED,
                "trace-1",
                OLD_NOW
        );

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenThrow(
                new ToolExecutionRegistrationScopeException()
        );

        when(transactions.recover(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenReturn(Optional.of(existing));

        RegisterToolExecutionResult result =
                service.register(command(false));

        assertEquals(9000L, result.toolExecutionId());
        assertFalse(result.newlyCreated());
        assertEquals(OLD_NOW, result.createdAt());
    }

    @Test
    void shouldThrowConversationNotFoundWhenHistoryMissing() {
        stubActorAndInputs();
        stubIdAndClock();

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenThrow(
                new ToolExecutionRegistrationScopeException()
        );

        when(transactions.recover(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenReturn(Optional.empty());

        assertThrows(
                ConversationNotFoundException.class,
                () -> service.register(command(false))
        );
    }

    @Test
    void shouldNotRecoverForOtherExceptions() {
        stubActorAndInputs();
        stubIdAndClock();

        IllegalStateException failure =
                new IllegalStateException("boom");

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenThrow(failure);

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.register(
                                command(false)
                        )
                );

        assertSame(failure, exception);

        verify(transactions, never()).recover(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        );
    }

    @Test
    void shouldFillTraceIdFromCorrelationWhenCommandTraceMissing() {
        stubActorAndInputs();
        stubIdAndClock();

        when(correlationProvider.currentCorrelation())
                .thenReturn(Optional.of(CORRELATION));

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenAnswer(invocation ->
                invocation.getArgument(1)
        );

        service.register(commandWithTrace(null));

        ArgumentCaptor<ToolExecutionRow> captor =
                ArgumentCaptor.forClass(ToolExecutionRow.class);

        verify(transactions).insert(
                any(CurrentActor.class),
                captor.capture()
        );

        assertEquals(
                "ctx-trace-1",
                captor.getValue().traceId()
        );
    }

    @Test
    void shouldPreferCommandTraceIdOverCorrelation() {
        stubActorAndInputs();
        stubIdAndClock();

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenAnswer(invocation ->
                invocation.getArgument(1)
        );

        service.register(commandWithTrace("explicit-trace"));

        ArgumentCaptor<ToolExecutionRow> captor =
                ArgumentCaptor.forClass(ToolExecutionRow.class);

        verify(transactions).insert(
                any(CurrentActor.class),
                captor.capture()
        );

        assertEquals(
                "explicit-trace",
                captor.getValue().traceId()
        );

        // 命令显式值优先：完全不需要查询 HTTP 上下文。
        verifyNoInteractions(correlationProvider);
    }

    @Test
    void shouldWriteNullTraceWithoutCorrelationContext() {
        stubActorAndInputs();
        stubIdAndClock();

        // Optional.empty() 表示正常无 HTTP 上下文，允许写入 null。
        when(correlationProvider.currentCorrelation())
                .thenReturn(Optional.empty());

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenAnswer(invocation ->
                invocation.getArgument(1)
        );

        service.register(commandWithTrace(null));

        ArgumentCaptor<ToolExecutionRow> captor =
                ArgumentCaptor.forClass(ToolExecutionRow.class);

        verify(transactions).insert(
                any(CurrentActor.class),
                captor.capture()
        );

        assertEquals(null, captor.getValue().traceId());
    }

    @Test
    void shouldPropagateCorrelationProviderFailure() {
        stubActorAndInputs();
        stubIdAndClock();

        // Provider 实现自身的真实故障：绝不能像旧实现那样
        // 捕获 IllegalStateException 并误判为"没有请求上下文"。
        IllegalStateException providerFailure =
                new IllegalStateException("provider boom");

        when(correlationProvider.currentCorrelation())
                .thenThrow(providerFailure);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> service.register(commandWithTrace(null))
        );

        assertSame(providerFailure, thrown);

        verify(transactions, never()).insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        );
    }

    @Test
    void shouldReplayWithCorrelationTraceWithoutConflict() {
        stubActorAndInputs();
        stubIdAndClock();

        ToolExecutionRow existing = row(
                9000L,
                ToolExecutionStatus.SUCCEEDED,
                "trace-1",
                OLD_NOW
        );

        when(correlationProvider.currentCorrelation())
                .thenReturn(Optional.of(CORRELATION));

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenThrow(new DuplicateKeyException("duplicate"));

        when(transactions.recover(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenReturn(Optional.of(existing));

        RegisterToolExecutionResult result =
                service.register(commandWithTrace(null));

        ArgumentCaptor<ToolExecutionRow> captor =
                ArgumentCaptor.forClass(ToolExecutionRow.class);

        verify(transactions).recover(
                any(CurrentActor.class),
                captor.capture()
        );

        // 重试携带新的上下文 traceId 不产生冲突……
        assertEquals(
                "ctx-trace-1",
                captor.getValue().traceId()
        );

        // ……且已持久化 execution 的原始 traceId 不更新：
        // 返回的仍是历史行（SUCCEEDED、旧 createdAt、非新建）。
        assertEquals(9000L, result.toolExecutionId());
        assertEquals(
                ToolExecutionStatus.SUCCEEDED,
                result.status()
        );
        assertEquals(OLD_NOW, result.createdAt());
        assertFalse(result.newlyCreated());
    }

    @Test
    void shouldCountRegisteredOutcome() {
        stubActorAndInputs();
        stubIdAndClock();

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenAnswer(invocation ->
                invocation.getArgument(1)
        );

        service.register(command(false));

        assertEquals(
                1.0,
                outcomeCount(
                        ToolExecutionMetrics.OUTCOME_REGISTERED,
                        false
                )
        );
        assertEquals(
                0.0,
                outcomeCount(
                        ToolExecutionMetrics.OUTCOME_REPLAYED,
                        true
                )
        );
    }

    @Test
    void shouldCountReplayedNotRegisteredOnDuplicateRecovery() {
        stubActorAndInputs();
        stubIdAndClock();

        ToolExecutionRow existing = row(
                9000L,
                ToolExecutionStatus.SUCCEEDED,
                "trace-1",
                OLD_NOW
        );

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenThrow(new DuplicateKeyException("duplicate"));

        when(transactions.recover(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenReturn(Optional.of(existing));

        service.register(command(false));

        // 幂等重放绝不统计成第二次业务成功。
        assertEquals(
                1.0,
                outcomeCount(
                        ToolExecutionMetrics.OUTCOME_REPLAYED,
                        true
                )
        );
        assertEquals(
                0.0,
                outcomeCount(
                        ToolExecutionMetrics.OUTCOME_REGISTERED,
                        false
                )
        );
    }

    @Test
    void shouldCountConflictOnIdempotencyConflict() {
        stubActorAndInputs();
        stubIdAndClock();

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenThrow(new DuplicateKeyException("duplicate"));

        when(transactions.recover(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenThrow(
                new com.nexusagent.tool.api
                        .ToolExecutionIdempotencyConflictException()
        );

        assertThrows(
                com.nexusagent.tool.api
                        .ToolExecutionIdempotencyConflictException.class,
                () -> service.register(command(false))
        );

        assertEquals(
                1.0,
                outcomeCount(
                        ToolExecutionMetrics.OUTCOME_CONFLICT,
                        true
                )
        );
    }

    @Test
    void shouldCountReplayedOnHistoricalRecovery() {
        stubActorAndInputs();
        stubIdAndClock();

        ToolExecutionRow existing = row(
                9000L,
                ToolExecutionStatus.SUCCEEDED,
                "trace-1",
                OLD_NOW
        );

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenThrow(
                new ToolExecutionRegistrationScopeException()
        );

        when(transactions.recover(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenReturn(Optional.of(existing));

        service.register(command(false));

        assertEquals(
                1.0,
                outcomeCount(
                        ToolExecutionMetrics.OUTCOME_REPLAYED,
                        true
                )
        );
        assertEquals(
                0.0,
                outcomeCount(
                        ToolExecutionMetrics.OUTCOME_REGISTERED,
                        false
                )
        );
    }

    @Test
    void shouldNotOverwriteHistoricalTraceOnReplay() {
        stubActorAndInputs();
        stubIdAndClock();

        ToolExecutionRow existing = row(
                9000L,
                ToolExecutionStatus.SUCCEEDED,
                "trace-1",
                OLD_NOW
        );

        when(transactions.insert(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenThrow(new DuplicateKeyException("duplicate"));

        when(transactions.recover(
                any(CurrentActor.class),
                any(ToolExecutionRow.class)
        )).thenReturn(Optional.of(existing));

        RegisterToolExecutionResult result =
                service.register(commandWithTrace("trace-2"));

        ArgumentCaptor<ToolExecutionRow> captor =
                ArgumentCaptor.forClass(ToolExecutionRow.class);

        verify(transactions).recover(
                any(CurrentActor.class),
                captor.capture()
        );

        assertEquals(
                "trace-2",
                captor.getValue().traceId()
        );
        assertEquals(OLD_NOW, result.createdAt());
        assertFalse(result.newlyCreated());
    }

    @Test
    void shouldRejectNullCommand() {
        assertThrows(
                NullPointerException.class,
                () -> service.register(null)
        );

        verifyNoInteractions(transactions);
    }

    private void stubActorAndInputs() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);

        when(inputJsonCodec.encode(
                any(JsonNode.class)
        )).thenReturn(CANONICAL_INPUT_JSON);

        when(keyFactory.create(
                TENANT_ID,
                CONVERSATION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                "call-1",
                "search"
        )).thenReturn(IDEMPOTENCY_KEY);
    }

    private void stubIdAndClock() {
        when(idGenerator.nextId())
                .thenReturn(EXECUTION_ID);

        when(clock.instant()).thenReturn(RAW_NOW);
    }

    private double outcomeCount(
            String outcome,
            boolean replayed
    ) {
        Counter counter = meterRegistry.find(
                        ToolExecutionMetrics.METRIC_NAME
                )
                .tag(
                        ToolExecutionMetrics.TAG_TOOL,
                        ToolExecutionMetrics.TOOL_CREATE_TICKET
                )
                .tag(
                        ToolExecutionMetrics.TAG_REPLAYED,
                        Boolean.toString(replayed)
                )
                .tag(
                        ToolExecutionMetrics.TAG_OUTCOME,
                        outcome
                )
                .counter();

        return counter == null ? 0.0 : counter.count();
    }

    private static RegisterToolExecutionCommand command(
            boolean approvalRequired
    ) {
        return new RegisterToolExecutionCommand(
                CONVERSATION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                "call-1",
                "search",
                OBJECT_MAPPER.createObjectNode()
                        .put("query", "latest"),
                approvalRequired,
                "trace-1",
                null
        );
    }

    private static RegisterToolExecutionCommand commandWithTrace(
            String traceId
    ) {
        return new RegisterToolExecutionCommand(
                CONVERSATION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                "call-1",
                "search",
                OBJECT_MAPPER.createObjectNode()
                        .put("query", "latest"),
                false,
                traceId,
                null
        );
    }

    private static ToolExecutionRow row(
            long id,
            ToolExecutionStatus status,
            String traceId,
            Instant createdAt
    ) {
        return new ToolExecutionRow(
                id,
                TENANT_ID,
                CONVERSATION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                null,
                "call-1",
                "search",
                IDEMPOTENCY_KEY,
                CANONICAL_INPUT_JSON,
                null,
                status,
                false,
                null,
                null,
                null,
                null,
                traceId,
                null,
                null,
                null,
                createdAt,
                createdAt
        );
    }
}
