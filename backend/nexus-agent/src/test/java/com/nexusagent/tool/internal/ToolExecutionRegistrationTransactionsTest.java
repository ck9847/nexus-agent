package com.nexusagent.tool.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.tool.api.ToolExecutionIdempotencyConflictException;
import com.nexusagent.tool.domain.ToolExecutionStatus;
import com.nexusagent.tool.internal.persistence.ToolExecutionMapper;
import com.nexusagent.tool.internal.persistence.ToolExecutionRegistrationScopeRow;
import com.nexusagent.tool.internal.persistence.ToolExecutionRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolExecutionRegistrationTransactionsTest {

    private static final long USER_ID = 101L;
    private static final long TENANT_ID = 202L;
    private static final long CONVERSATION_ID = 901L;
    private static final long AGENT_ID = 500L;
    private static final long REQUEST_MESSAGE_ID = 1001L;
    private static final long EXECUTION_ID = 7001L;

    private static final String IDEMPOTENCY_KEY =
            "tool:v1:" + "a".repeat(64);

    private static final String INPUT_JSON =
            "{\"query\":\"latest\"}";

    private static final String ALTERNATE_INPUT_JSON =
            "{\"query\":\"old\"}";

    private static final String ALTERNATE_WHITESPACE_INPUT_JSON =
            "{\"query\": \"latest\"}";

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-13T10:15:30.123Z"
            );

    private static final CurrentActor ACTOR =
            new CurrentActor(
                    USER_ID,
                    TENANT_ID,
                    "member",
                    Set.of("MEMBER")
            );

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper();

    private static final JsonNode INPUT_NODE =
            parse(INPUT_JSON);

    private static final JsonNode ALTERNATE_INPUT_NODE =
            parse(ALTERNATE_INPUT_JSON);

    @Mock
    private ToolExecutionMapper toolExecutionMapper;

    @Mock
    private ToolInputJsonCodec inputJsonCodec;

    @Mock
    private AuditLogWriter auditLogWriter;

    private ToolExecutionRegistrationTransactions transactions;

    @BeforeEach
    void setUp() {
        transactions =
                new ToolExecutionRegistrationTransactions(
                        toolExecutionMapper,
                        inputJsonCodec,
                        auditLogWriter
                );
    }

    @Test
    void shouldInsertCandidateAndAuditOnce() {
        stubScope();

        when(toolExecutionMapper.insert(
                any(ToolExecutionRow.class)
        )).thenReturn(1);

        ToolExecutionRow candidate = candidate();

        ToolExecutionRow inserted =
                transactions.insert(ACTOR, candidate);

        assertSame(candidate, inserted);

        verify(toolExecutionMapper).insert(candidate);
        verify(auditLogWriter).write(
                any(AuditLogCommand.class)
        );
    }

    @Test
    void shouldAuditAsAgentWithIdsAndNoSensitiveData() {
        stubScope();

        when(toolExecutionMapper.insert(
                any(ToolExecutionRow.class)
        )).thenReturn(1);

        transactions.insert(ACTOR, candidate());

        ArgumentCaptor<AuditLogCommand> captor =
                ArgumentCaptor.forClass(
                        AuditLogCommand.class
                );

        verify(auditLogWriter).write(captor.capture());

        AuditLogCommand audit = captor.getValue();

        assertEquals(TENANT_ID, audit.tenantId());
        assertEquals(
                AuditActorType.AGENT,
                audit.actorType()
        );
        assertEquals(AGENT_ID, audit.actorId());
        assertEquals(
                "TOOL_EXECUTION_REGISTERED",
                audit.action()
        );
        assertEquals(
                "TOOL_EXECUTION",
                audit.resourceType()
        );
        assertEquals(EXECUTION_ID, audit.resourceId());
        assertEquals(
                EXECUTION_ID,
                audit.toolExecutionId()
        );
        assertEquals(AuditResult.SUCCESS, audit.result());
        assertNull(audit.requestId());
        assertEquals("trace-1", audit.traceId());
        assertNull(audit.ipAddress());
        assertNull(audit.beforeData());
        assertNull(audit.errorCode());
        assertNull(audit.errorMessage());

        Map<String, Object> afterData =
                (Map<String, Object>) audit.afterData();

        assertNotNull(afterData);
        assertFalse(afterData.containsKey("inputJson"));
        assertEquals(
                Long.toString(CONVERSATION_ID),
                afterData.get("conversationId")
        );
        assertEquals(
                Long.toString(AGENT_ID),
                afterData.get("agentId")
        );
        assertEquals(
                Long.toString(USER_ID),
                afterData.get("requestedByUserId")
        );
        assertEquals(
                Long.toString(REQUEST_MESSAGE_ID),
                afterData.get("requestMessageId")
        );
        assertEquals(
                "call-1",
                afterData.get("toolCallId")
        );
        assertEquals(
                "search",
                afterData.get("toolName")
        );
        assertEquals(
                "PENDING",
                afterData.get("status")
        );
        assertEquals(
                false,
                afterData.get("approvalRequired")
        );
    }

    @Test
    void shouldThrowWhenScopeMissing() {
        when(toolExecutionMapper
                .findRegistrationScopeForUpdate(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        AGENT_ID,
                        REQUEST_MESSAGE_ID
                )).thenReturn(Optional.empty());

        assertThrows(
                ToolExecutionRegistrationScopeException.class,
                () -> transactions.insert(
                        ACTOR,
                        candidate()
                )
        );

        verify(toolExecutionMapper, never()).insert(
                any(ToolExecutionRow.class)
        );
        verifyNoInteractions(auditLogWriter);
    }

    @ParameterizedTest
    @MethodSource("mismatchedScopes")
    void shouldRejectMismatchedScope(
            ToolExecutionRegistrationScopeRow scope
    ) {
        when(toolExecutionMapper
                .findRegistrationScopeForUpdate(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        AGENT_ID,
                        REQUEST_MESSAGE_ID
                )).thenReturn(Optional.of(scope));

        assertThrows(
                IllegalStateException.class,
                () -> transactions.insert(
                        ACTOR,
                        candidate()
                )
        );

        verify(toolExecutionMapper, never()).insert(
                any(ToolExecutionRow.class)
        );
        verifyNoInteractions(auditLogWriter);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void shouldThrowWhenInsertAffectsUnexpectedRows(
            int affectedRows
    ) {
        stubScope();

        when(toolExecutionMapper.insert(
                any(ToolExecutionRow.class)
        )).thenReturn(affectedRows);

        assertThrows(
                IllegalStateException.class,
                () -> transactions.insert(
                        ACTOR,
                        candidate()
                )
        );

        verify(auditLogWriter, never()).write(
                any(AuditLogCommand.class)
        );
    }

    @Test
    void shouldRejectNullActorOnInsert() {
        assertThrows(
                NullPointerException.class,
                () -> transactions.insert(
                        null,
                        candidate()
                )
        );

        verifyNoInteractions(
                toolExecutionMapper,
                auditLogWriter
        );
    }

    @Test
    void shouldRejectNullCandidateOnInsert() {
        assertThrows(
                NullPointerException.class,
                () -> transactions.insert(ACTOR, null)
        );

        verifyNoInteractions(
                toolExecutionMapper,
                auditLogWriter
        );
    }

    @Test
    void shouldRejectForeignTenantCandidateOnInsert() {
        assertThrows(
                IllegalArgumentException.class,
                () -> transactions.insert(
                        ACTOR,
                        rowForTenant(TENANT_ID + 1)
                )
        );

        verifyNoInteractions(
                toolExecutionMapper,
                auditLogWriter
        );
    }

    @Test
    void shouldRejectNullActorOnRecover() {
        assertThrows(
                NullPointerException.class,
                () -> transactions.recover(
                        null,
                        candidate()
                )
        );

        verifyNoInteractions(
                toolExecutionMapper,
                auditLogWriter
        );
    }

    @Test
    void shouldReturnEmptyWhenNoRowsExist() {
        stubEmptyReplayQueries();

        assertEquals(
                Optional.empty(),
                transactions.recover(
                        ACTOR,
                        candidate()
                )
        );
    }

    @Test
    void shouldLockConversationBeforeRecoveringExecution() {
        ToolExecutionRow existing = candidate();

        stubReplayQueries(existing);

        when(inputJsonCodec.decode(INPUT_JSON))
                .thenReturn(INPUT_NODE);

        transactions.recover(ACTOR, candidate());

        InOrder inOrder = inOrder(toolExecutionMapper);

        inOrder.verify(toolExecutionMapper)
                .lockOwnedConversationForRecovery(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        AGENT_ID
                );
        inOrder.verify(toolExecutionMapper)
                .findByIdempotencyKeyForUpdate(
                        TENANT_ID,
                        IDEMPOTENCY_KEY
                );
        inOrder.verify(toolExecutionMapper)
                .findByConversationAndToolCallIdForUpdate(
                        TENANT_ID,
                        CONVERSATION_ID,
                        "call-1"
                );
    }

    @Test
    void shouldReturnEmptyWithoutReadingExecutionsWhenConversationIsNotOwned() {
        when(toolExecutionMapper
                .lockOwnedConversationForRecovery(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        AGENT_ID
                )).thenReturn(Optional.empty());

        assertEquals(
                Optional.empty(),
                transactions.recover(
                        ACTOR,
                        candidate()
                )
        );

        verify(toolExecutionMapper, never())
                .findByIdempotencyKeyForUpdate(
                        anyLong(),
                        any()
                );
        verify(toolExecutionMapper, never())
                .findByConversationAndToolCallIdForUpdate(
                        anyLong(),
                        anyLong(),
                        any()
                );
    }

    @Test
    void shouldRejectMismatchedRecoveredConversationId() {
        when(toolExecutionMapper
                .lockOwnedConversationForRecovery(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        AGENT_ID
                )).thenReturn(Optional.of(
                        CONVERSATION_ID + 1L
                ));

        assertThrows(
                IllegalStateException.class,
                () -> transactions.recover(
                        ACTOR,
                        candidate()
                )
        );

        verify(toolExecutionMapper, never())
                .findByIdempotencyKeyForUpdate(
                        anyLong(),
                        any()
                );
        verify(toolExecutionMapper, never())
                .findByConversationAndToolCallIdForUpdate(
                        anyLong(),
                        anyLong(),
                        any()
                );
    }

    @Test
    void shouldConflictWhenOnlyKeyMatches() {
        stubConversationRecoveryLock();

        when(toolExecutionMapper
                .findByIdempotencyKeyForUpdate(
                        TENANT_ID,
                        IDEMPOTENCY_KEY
                )).thenReturn(Optional.of(candidate()));

        when(toolExecutionMapper
                .findByConversationAndToolCallIdForUpdate(
                        TENANT_ID,
                        CONVERSATION_ID,
                        "call-1"
                )).thenReturn(Optional.empty());

        assertThrows(
                ToolExecutionIdempotencyConflictException.class,
                () -> transactions.recover(
                        ACTOR,
                        candidate()
                )
        );
    }

    @Test
    void shouldConflictWhenOnlyCallMatches() {
        stubConversationRecoveryLock();

        when(toolExecutionMapper
                .findByIdempotencyKeyForUpdate(
                        TENANT_ID,
                        IDEMPOTENCY_KEY
                )).thenReturn(Optional.empty());

        when(toolExecutionMapper
                .findByConversationAndToolCallIdForUpdate(
                        TENANT_ID,
                        CONVERSATION_ID,
                        "call-1"
                )).thenReturn(Optional.of(candidate()));

        assertThrows(
                ToolExecutionIdempotencyConflictException.class,
                () -> transactions.recover(
                        ACTOR,
                        candidate()
                )
        );
    }

    @Test
    void shouldConflictWhenRowsHaveDifferentIds() {
        stubConversationRecoveryLock();

        when(toolExecutionMapper
                .findByIdempotencyKeyForUpdate(
                        TENANT_ID,
                        IDEMPOTENCY_KEY
                )).thenReturn(Optional.of(rowWithId(1L)));

        when(toolExecutionMapper
                .findByConversationAndToolCallIdForUpdate(
                        TENANT_ID,
                        CONVERSATION_ID,
                        "call-1"
                )).thenReturn(Optional.of(rowWithId(2L)));

        assertThrows(
                ToolExecutionIdempotencyConflictException.class,
                () -> transactions.recover(
                        ACTOR,
                        candidate()
                )
        );
    }

    @Test
    void shouldReplayIdenticalRecord() {
        ToolExecutionRow existing = candidate();

        stubReplayQueries(existing);

        when(inputJsonCodec.decode(INPUT_JSON))
                .thenReturn(INPUT_NODE);

        Optional<ToolExecutionRow> recovered =
                transactions.recover(ACTOR, candidate());

        assertTrue(recovered.isPresent());
        assertSame(existing, recovered.get());

        InOrder inOrder = inOrder(toolExecutionMapper);

        inOrder.verify(toolExecutionMapper)
                .lockOwnedConversationForRecovery(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        AGENT_ID
                );
        inOrder.verify(toolExecutionMapper)
                .findByIdempotencyKeyForUpdate(
                        TENANT_ID,
                        IDEMPOTENCY_KEY
                );
        inOrder.verify(toolExecutionMapper)
                .findByConversationAndToolCallIdForUpdate(
                        TENANT_ID,
                        CONVERSATION_ID,
                        "call-1"
                );
    }

    @Test
    void shouldReplayCanonicalEquivalentInput() {
        ToolExecutionRow existing = candidate();

        ToolExecutionRow retried = row(
                EXECUTION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                "search",
                false,
                ALTERNATE_WHITESPACE_INPUT_JSON,
                "trace-1"
        );

        stubReplayQueries(existing);

        when(inputJsonCodec.decode(INPUT_JSON))
                .thenReturn(INPUT_NODE);
        when(inputJsonCodec.decode(
                ALTERNATE_WHITESPACE_INPUT_JSON
        )).thenReturn(parse(ALTERNATE_WHITESPACE_INPUT_JSON));

        Optional<ToolExecutionRow> recovered =
                transactions.recover(ACTOR, retried);

        assertTrue(recovered.isPresent());
        assertSame(existing, recovered.get());
    }

    @ParameterizedTest
    @MethodSource("mismatchedReplays")
    void shouldConflictWhenImmutableFieldDiffers(
            ToolExecutionRow mismatched
    ) {
        ToolExecutionRow existing = candidate();

        when(toolExecutionMapper
                .lockOwnedConversationForRecovery(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        mismatched.agentId()
                )).thenReturn(Optional.of(
                        CONVERSATION_ID
                ));

        when(toolExecutionMapper
                .findByIdempotencyKeyForUpdate(
                        TENANT_ID,
                        IDEMPOTENCY_KEY
                )).thenReturn(Optional.of(existing));

        when(toolExecutionMapper
                .findByConversationAndToolCallIdForUpdate(
                        TENANT_ID,
                        CONVERSATION_ID,
                        "call-1"
                )).thenReturn(Optional.of(existing));

        assertThrows(
                ToolExecutionIdempotencyConflictException.class,
                () -> transactions.recover(
                        ACTOR,
                        mismatched
                )
        );
    }

    @Test
    void shouldConflictWhenInputDiffers() {
        ToolExecutionRow existing = candidate();

        ToolExecutionRow mismatched = row(
                EXECUTION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                "search",
                false,
                ALTERNATE_INPUT_JSON,
                "trace-1"
        );

        stubReplayQueries(existing);

        when(inputJsonCodec.decode(INPUT_JSON))
                .thenReturn(INPUT_NODE);
        when(inputJsonCodec.decode(ALTERNATE_INPUT_JSON))
                .thenReturn(ALTERNATE_INPUT_NODE);

        assertThrows(
                ToolExecutionIdempotencyConflictException.class,
                () -> transactions.recover(
                        ACTOR,
                        mismatched
                )
        );
    }

    @Test
    void shouldAllowReplayWithDifferentTraceId() {
        ToolExecutionRow existing = candidate();

        ToolExecutionRow retried = row(
                EXECUTION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                "search",
                false,
                INPUT_JSON,
                "trace-2"
        );

        stubReplayQueries(existing);

        when(inputJsonCodec.decode(INPUT_JSON))
                .thenReturn(INPUT_NODE);

        Optional<ToolExecutionRow> recovered =
                transactions.recover(ACTOR, retried);

        assertTrue(recovered.isPresent());
        assertSame(existing, recovered.get());
    }

    private void stubScope() {
        when(toolExecutionMapper
                .findRegistrationScopeForUpdate(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        AGENT_ID,
                        REQUEST_MESSAGE_ID
                )).thenReturn(Optional.of(scope()));
    }

    private void stubConversationRecoveryLock() {
        when(toolExecutionMapper
                .lockOwnedConversationForRecovery(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        AGENT_ID
                )).thenReturn(Optional.of(
                        CONVERSATION_ID
                ));
    }

    private void stubEmptyReplayQueries() {
        stubConversationRecoveryLock();

        when(toolExecutionMapper
                .findByIdempotencyKeyForUpdate(
                        TENANT_ID,
                        IDEMPOTENCY_KEY
                )).thenReturn(Optional.empty());

        when(toolExecutionMapper
                .findByConversationAndToolCallIdForUpdate(
                        TENANT_ID,
                        CONVERSATION_ID,
                        "call-1"
                )).thenReturn(Optional.empty());
    }

    private void stubReplayQueries(ToolExecutionRow existing) {
        stubConversationRecoveryLock();

        when(toolExecutionMapper
                .findByIdempotencyKeyForUpdate(
                        TENANT_ID,
                        IDEMPOTENCY_KEY
                )).thenReturn(Optional.of(existing));

        when(toolExecutionMapper
                .findByConversationAndToolCallIdForUpdate(
                        TENANT_ID,
                        CONVERSATION_ID,
                        "call-1"
                )).thenReturn(Optional.of(existing));
    }

    private static ToolExecutionRegistrationScopeRow scope() {
        return new ToolExecutionRegistrationScopeRow(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID
        );
    }

    private static ToolExecutionRow candidate() {
        return row(
                EXECUTION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                "search",
                false,
                INPUT_JSON,
                "trace-1"
        );
    }

    private static ToolExecutionRow rowWithId(long id) {
        return row(
                id,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                "search",
                false,
                INPUT_JSON,
                "trace-1"
        );
    }

    private static ToolExecutionRow rowForTenant(long tenantId) {
        return new ToolExecutionRow(
                EXECUTION_ID,
                tenantId,
                CONVERSATION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                null,
                "call-1",
                "search",
                IDEMPOTENCY_KEY,
                INPUT_JSON,
                null,
                ToolExecutionStatus.PENDING,
                false,
                null,
                null,
                null,
                null,
                "trace-1",
                null,
                null,
                null,
                NOW,
                NOW
        );
    }

    private static ToolExecutionRow row(
            long id,
            long agentId,
            Long requestMessageId,
            String toolName,
            boolean approvalRequired,
            String inputJson,
            String traceId
    ) {
        return new ToolExecutionRow(
                id,
                TENANT_ID,
                CONVERSATION_ID,
                agentId,
                requestMessageId,
                null,
                "call-1",
                toolName,
                IDEMPOTENCY_KEY,
                inputJson,
                null,
                ToolExecutionStatus.PENDING,
                approvalRequired,
                null,
                null,
                null,
                null,
                traceId,
                null,
                null,
                null,
                NOW,
                NOW
        );
    }

    private static Stream<Arguments> mismatchedScopes() {
        return Stream.of(
                Arguments.of(
                        new ToolExecutionRegistrationScopeRow(
                                CONVERSATION_ID + 1,
                                TENANT_ID,
                                USER_ID,
                                AGENT_ID,
                                REQUEST_MESSAGE_ID
                        )
                ),
                Arguments.of(
                        new ToolExecutionRegistrationScopeRow(
                                CONVERSATION_ID,
                                TENANT_ID + 1,
                                USER_ID,
                                AGENT_ID,
                                REQUEST_MESSAGE_ID
                        )
                ),
                Arguments.of(
                        new ToolExecutionRegistrationScopeRow(
                                CONVERSATION_ID,
                                TENANT_ID,
                                USER_ID + 1,
                                AGENT_ID,
                                REQUEST_MESSAGE_ID
                        )
                ),
                Arguments.of(
                        new ToolExecutionRegistrationScopeRow(
                                CONVERSATION_ID,
                                TENANT_ID,
                                USER_ID,
                                AGENT_ID + 1,
                                REQUEST_MESSAGE_ID
                        )
                ),
                Arguments.of(
                        new ToolExecutionRegistrationScopeRow(
                                CONVERSATION_ID,
                                TENANT_ID,
                                USER_ID,
                                AGENT_ID,
                                REQUEST_MESSAGE_ID + 1
                        )
                )
        );
    }

    private static Stream<Arguments> mismatchedReplays() {
        return Stream.of(
                Arguments.of(
                        row(
                                EXECUTION_ID,
                                AGENT_ID + 1,
                                REQUEST_MESSAGE_ID,
                                "search",
                                false,
                                INPUT_JSON,
                                "trace-1"
                        )
                ),
                Arguments.of(
                        row(
                                EXECUTION_ID,
                                AGENT_ID,
                                REQUEST_MESSAGE_ID + 1,
                                "search",
                                false,
                                INPUT_JSON,
                                "trace-1"
                        )
                ),
                Arguments.of(
                        row(
                                EXECUTION_ID,
                                AGENT_ID,
                                REQUEST_MESSAGE_ID,
                                "search_web",
                                false,
                                INPUT_JSON,
                                "trace-1"
                        )
                ),
                Arguments.of(
                        row(
                                EXECUTION_ID,
                                AGENT_ID,
                                REQUEST_MESSAGE_ID,
                                "search",
                                true,
                                INPUT_JSON,
                                "trace-1"
                        )
                )
        );
    }

    private static JsonNode parse(String json) {
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }
}
