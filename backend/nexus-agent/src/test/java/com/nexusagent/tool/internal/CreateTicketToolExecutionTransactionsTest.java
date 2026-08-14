package com.nexusagent.tool.internal;

import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.common.id.IdGenerator;
import com.nexusagent.conversation.api.ConversationNotActiveException;
import com.nexusagent.conversation.api.ConversationNotFoundException;
import com.nexusagent.conversation.domain.ConversationStatus;
import com.nexusagent.conversation.domain.MessageContentType;
import com.nexusagent.conversation.domain.MessageRole;
import com.nexusagent.conversation.domain.MessageStatus;
import com.nexusagent.conversation.internal.ConversationTurnMetadataJsonCodec;
import com.nexusagent.conversation.internal.persistence.ConversationMapper;
import com.nexusagent.conversation.internal.persistence.ConversationTurnStateRow;
import com.nexusagent.conversation.internal.persistence.MessageMapper;
import com.nexusagent.conversation.internal.persistence.MessageRow;
import com.nexusagent.ticket.api.CreateTicketResponse;
import com.nexusagent.ticket.domain.TicketPriority;
import com.nexusagent.ticket.domain.TicketStatus;
import com.nexusagent.tool.api.ToolExecutionApprovalRequiredException;
import com.nexusagent.tool.api.ToolExecutionInProgressException;
import com.nexusagent.tool.api.ToolExecutionNotFoundException;
import com.nexusagent.tool.api.ToolExecutionTerminalStateException;
import com.nexusagent.tool.domain.ToolExecutionStatus;
import com.nexusagent.tool.internal.persistence.ToolExecutionMapper;
import com.nexusagent.tool.internal.persistence.ToolExecutionRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateTicketToolExecutionTransactionsTest {

    private static final long TENANT_ID = 202L;
    private static final long REQUESTER_USER_ID = 101L;
    private static final long CONVERSATION_ID = 901L;
    private static final long AGENT_ID = 500L;
    private static final long REQUEST_MESSAGE_ID = 1001L;
    private static final long TOOL_EXECUTION_ID = 7001L;
    private static final long TOOL_MESSAGE_ID = 8001L;
    private static final long FINAL_ASSISTANT_MESSAGE_ID = 8002L;
    private static final long TICKET_ID = 9001L;

    private static final String IDEMPOTENCY_KEY =
            "tool:v1:" + "a".repeat(64);

    private static final String INPUT_JSON =
            "{\"title\":\"Server down\","
                    + "\"description\":\"It is down\","
                    + "\"priority\":\"HIGH\"}";

    private static final String OUTPUT_JSON =
            "{\"ticketId\":\"9001\","
                    + "\"ticketNo\":\"TKT-A1\","
                    + "\"status\":\"OPEN\"}";

    private static final Instant NOW =
            Instant.parse("2026-08-13T10:15:30.123Z");

    private static final Instant LATER =
            Instant.parse("2026-08-13T10:15:32.123Z");

    @Mock
    private ConversationMapper conversationMapper;

    @Mock
    private ToolExecutionMapper toolExecutionMapper;

    @Mock
    private CreateTicketToolJsonCodec ticketToolJsonCodec;

    @Mock
    private CreateTicketAgentTool createTicketAgentTool;

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private ConversationTurnMetadataJsonCodec metadataCodec;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private AuditLogWriter auditLogWriter;

    private CreateTicketToolExecutionTransactions transactions;

    @BeforeEach
    void setUp() {
        transactions =
                new CreateTicketToolExecutionTransactions(
                        conversationMapper,
                        toolExecutionMapper,
                        ticketToolJsonCodec,
                        createTicketAgentTool,
                        messageMapper,
                        metadataCodec,
                        idGenerator,
                        auditLogWriter,
                        Clock.fixed(NOW, ZoneOffset.UTC)
                );
    }

    @Test
    void shouldLockConversationBeforeExecutionOnClaim() {
        stubConversationLock();
        stubExecutionLock(row(ToolExecutionStatus.PENDING));
        stubPendingClaimSuccesses();

        when(ticketToolJsonCodec.decodeArguments(INPUT_JSON))
                .thenReturn(arguments());

        transactions.claim(context());

        InOrder inOrder = inOrder(
                conversationMapper,
                toolExecutionMapper
        );

        inOrder.verify(conversationMapper)
                .findOwnedTurnForUpdate(
                        CONVERSATION_ID,
                        TENANT_ID,
                        REQUESTER_USER_ID
                );
        inOrder.verify(toolExecutionMapper)
                .findByTenantIdAndIdForUpdate(
                        TENANT_ID,
                        TOOL_EXECUTION_ID
                );
    }

    @Test
    void shouldClaimPendingAndMarkRunning() {
        stubConversationLock();
        stubExecutionLock(row(ToolExecutionStatus.PENDING));
        stubPendingClaimSuccesses();

        when(ticketToolJsonCodec.decodeArguments(INPUT_JSON))
                .thenReturn(arguments());

        ClaimedCreateTicketToolExecution claim =
                transactions.claim(context());

        assertNull(claim.replayResult());
        assertEquals(
                arguments(),
                claim.arguments()
        );
        assertEquals(NOW, claim.startedAt());

        verify(toolExecutionMapper).markRunning(
                TENANT_ID,
                TOOL_EXECUTION_ID,
                CONVERSATION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                "call-1",
                NOW
        );

        ArgumentCaptor<AuditLogCommand> auditCaptor =
                ArgumentCaptor.forClass(AuditLogCommand.class);

        verify(auditLogWriter).write(auditCaptor.capture());

        AuditLogCommand audit = auditCaptor.getValue();

        assertEquals(
                AuditActorType.AGENT,
                audit.actorType()
        );
        assertEquals(AGENT_ID, audit.actorId());
        assertEquals(
                "TOOL_EXECUTION_STARTED",
                audit.action()
        );
        assertEquals(
                "TOOL_EXECUTION",
                audit.resourceType()
        );
        assertEquals(
                TOOL_EXECUTION_ID,
                audit.resourceId()
        );
        assertEquals(
                TOOL_EXECUTION_ID,
                audit.toolExecutionId()
        );

        Map<String, Object> afterData =
                (Map<String, Object>) audit.afterData();

        assertFalse(afterData.containsKey("input"));
        assertFalse(afterData.containsKey("title"));
        assertFalse(afterData.containsKey("description"));
    }

    @Test
    void shouldRejectWaitingApprovalClaimWithoutRunning() {
        stubConversationLock();
        stubExecutionLock(
                row(ToolExecutionStatus.WAITING_APPROVAL)
        );

        assertThrows(
                ToolExecutionApprovalRequiredException.class,
                () -> transactions.claim(context())
        );

        verify(toolExecutionMapper, never()).markRunning(
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(),
                any()
        );
        verifyNoInteractions(
                createTicketAgentTool,
                auditLogWriter
        );
    }

    @Test
    void shouldRejectRunningClaimWithoutReexecution() {
        stubConversationLock();
        stubExecutionLock(row(ToolExecutionStatus.RUNNING));

        assertThrows(
                ToolExecutionInProgressException.class,
                () -> transactions.claim(context())
        );

        verify(toolExecutionMapper, never()).markRunning(
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(),
                any()
        );
        verifyNoInteractions(createTicketAgentTool);
    }

    @Test
    void shouldReplaySucceededClaimWithoutCreatingTicket() {
        stubConversationLock();
        stubExecutionLock(succeededRow());
        stubReplayMessageReads();

        when(ticketToolJsonCodec.decodeOutput(OUTPUT_JSON))
                .thenReturn(output());

        ClaimedCreateTicketToolExecution claim =
                transactions.claim(context());

        ExecuteCreateTicketToolResult replay =
                claim.replayResult();

        assertEquals(TOOL_EXECUTION_ID, replay.toolExecutionId());
        assertEquals("9001", replay.ticketId());
        assertEquals("TKT-A1", replay.ticketNo());
        assertEquals(TicketStatus.OPEN, replay.ticketStatus());
        assertEquals(TOOL_MESSAGE_ID, replay.resultMessageId());
        assertEquals(3L, replay.resultMessageSequenceNo());
        assertEquals(
                FINAL_ASSISTANT_MESSAGE_ID,
                replay.assistantMessageId()
        );
        assertEquals(4L, replay.assistantSequenceNo());
        assertEquals(1, replay.conversationVersion());
        assertEquals(NOW, replay.assistantPreparedAt());
        assertTrue(replay.replayed());
        assertNull(claim.arguments());

        verifyNoInteractions(createTicketAgentTool);
        verify(toolExecutionMapper, never()).markRunning(
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(),
                any()
        );
        verify(toolExecutionMapper, never()).markSucceeded(
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(),
                any(Long.class),
                any(),
                any(Long.class),
                any(),
                any(Long.class)
        );
        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldReplaySucceededWhenConversationArchived() {
        stubConversationLockWithStatus(
                ConversationStatus.ARCHIVED
        );
        stubExecutionLock(succeededRow());
        stubReplayMessageReads();

        when(ticketToolJsonCodec.decodeOutput(OUTPUT_JSON))
                .thenReturn(output());

        ClaimedCreateTicketToolExecution claim =
                transactions.claim(context());

        ExecuteCreateTicketToolResult replay =
                claim.replayResult();

        assertEquals("9001", replay.ticketId());
        assertEquals("TKT-A1", replay.ticketNo());
        assertEquals(TOOL_MESSAGE_ID, replay.resultMessageId());
        assertEquals(3L, replay.resultMessageSequenceNo());
        assertEquals(
                FINAL_ASSISTANT_MESSAGE_ID,
                replay.assistantMessageId()
        );
        assertEquals(4L, replay.assistantSequenceNo());
        assertEquals(1, replay.conversationVersion());
        assertEquals(NOW, replay.assistantPreparedAt());
        assertTrue(replay.replayed());

        verify(toolExecutionMapper, never()).markRunning(
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(),
                any()
        );
        verifyNoInteractions(createTicketAgentTool);
        verifyNoInteractions(auditLogWriter);

        InOrder inOrder = inOrder(
                conversationMapper,
                toolExecutionMapper
        );

        inOrder.verify(conversationMapper)
                .findOwnedTurnForUpdate(
                        CONVERSATION_ID,
                        TENANT_ID,
                        REQUESTER_USER_ID
                );
        inOrder.verify(toolExecutionMapper)
                .findByTenantIdAndIdForUpdate(
                        TENANT_ID,
                        TOOL_EXECUTION_ID
                );
    }

    @Test
    void shouldReplaySucceededWhenConversationCompleted() {
        stubConversationLockWithStatus(
                ConversationStatus.COMPLETED
        );
        stubExecutionLock(succeededRow());
        stubReplayMessageReads();

        when(ticketToolJsonCodec.decodeOutput(OUTPUT_JSON))
                .thenReturn(output());

        ClaimedCreateTicketToolExecution claim =
                transactions.claim(context());

        ExecuteCreateTicketToolResult replay =
                claim.replayResult();

        assertEquals("9001", replay.ticketId());
        assertEquals("TKT-A1", replay.ticketNo());
        assertEquals(TOOL_MESSAGE_ID, replay.resultMessageId());
        assertEquals(3L, replay.resultMessageSequenceNo());
        assertEquals(
                FINAL_ASSISTANT_MESSAGE_ID,
                replay.assistantMessageId()
        );
        assertEquals(4L, replay.assistantSequenceNo());
        assertEquals(1, replay.conversationVersion());
        assertEquals(NOW, replay.assistantPreparedAt());
        assertTrue(replay.replayed());

        verify(toolExecutionMapper, never()).markRunning(
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(),
                any()
        );
        verifyNoInteractions(createTicketAgentTool);
        verifyNoInteractions(auditLogWriter);

        InOrder inOrder = inOrder(
                conversationMapper,
                toolExecutionMapper
        );

        inOrder.verify(conversationMapper)
                .findOwnedTurnForUpdate(
                        CONVERSATION_ID,
                        TENANT_ID,
                        REQUESTER_USER_ID
                );
        inOrder.verify(toolExecutionMapper)
                .findByTenantIdAndIdForUpdate(
                        TENANT_ID,
                        TOOL_EXECUTION_ID
                );
    }

    @Test
    void shouldNotDecodeInputWhenReplayingSucceededExecution() {
        stubConversationLock();
        stubExecutionLock(succeededRow());
        stubReplayMessageReads();

        when(ticketToolJsonCodec.decodeOutput(OUTPUT_JSON))
                .thenReturn(output());

        ClaimedCreateTicketToolExecution claim =
                transactions.claim(context());

        assertTrue(claim.replayResult().replayed());

        verify(ticketToolJsonCodec, never())
                .decodeArguments(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidReplayMessages")
    void shouldRejectReplayWithInvalidMessage(
            String description,
            ToolCallRequestMessageRow toolMessage,
            ToolCallRequestMessageRow assistantMessage
    ) {
        stubConversationLock();
        stubExecutionLock(succeededRow());

        when(messageMapper.findOwnedMessageByIdForUpdate(
                TOOL_MESSAGE_ID,
                TENANT_ID,
                CONVERSATION_ID
        )).thenReturn(Optional.of(toolMessage));

        when(messageMapper
                .findOwnedMessageBySequenceForUpdate(
                        TENANT_ID,
                        CONVERSATION_ID,
                        toolMessage.sequenceNo() + 1
                )).thenReturn(Optional.of(
                        assistantMessage
                ));

        when(ticketToolJsonCodec.decodeOutput(OUTPUT_JSON))
                .thenReturn(output());

        assertThrows(
                IllegalStateException.class,
                () -> transactions.claim(context())
        );

        verifyNoInteractions(createTicketAgentTool);
        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldRejectPendingWhenConversationIsNotActive() {
        stubConversationLockWithStatus(
                ConversationStatus.ARCHIVED
        );
        stubExecutionLock(row(ToolExecutionStatus.PENDING));

        assertThrows(
                ConversationNotActiveException.class,
                () -> transactions.claim(context())
        );

        verify(ticketToolJsonCodec, never())
                .decodeArguments(any());
        verify(toolExecutionMapper, never()).markRunning(
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(),
                any()
        );
        verifyNoInteractions(auditLogWriter);
    }

    @ParameterizedTest
    @MethodSource("terminalStatuses")
    void shouldRejectTerminalClaims(
            ToolExecutionStatus status
    ) {
        stubConversationLock();
        stubExecutionLock(row(status));

        assertThrows(
                ToolExecutionTerminalStateException.class,
                () -> transactions.claim(context())
        );

        verify(toolExecutionMapper, never()).markRunning(
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(),
                any()
        );
    }

    @Test
    void shouldThrowNotFoundWhenExecutionMissing() {
        stubConversationLock();

        when(toolExecutionMapper
                .findByTenantIdAndIdForUpdate(
                        TENANT_ID,
                        TOOL_EXECUTION_ID
                )).thenReturn(Optional.empty());

        assertThrows(
                ToolExecutionNotFoundException.class,
                () -> transactions.claim(context())
        );
    }

    @Test
    void shouldThrowNotFoundWhenConversationMissing() {
        when(conversationMapper.findOwnedTurnForUpdate(
                CONVERSATION_ID,
                TENANT_ID,
                REQUESTER_USER_ID
        )).thenReturn(Optional.empty());

        assertThrows(
                ConversationNotFoundException.class,
                () -> transactions.claim(context())
        );

        verifyNoInteractions(toolExecutionMapper);
    }

    @ParameterizedTest
    @MethodSource("mismatchedExecutions")
    void shouldRejectMismatchedExecutionsOnClaim(
            ToolExecutionRow mismatched
    ) {
        stubConversationLock();
        stubExecutionLock(mismatched);

        assertThrows(
                IllegalStateException.class,
                () -> transactions.claim(context())
        );

        verify(toolExecutionMapper, never()).markRunning(
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(),
                any()
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void shouldRejectMarkRunningUnexpectedRows(
            int affectedRows
    ) {
        stubConversationLock();
        stubExecutionLock(row(ToolExecutionStatus.PENDING));

        when(ticketToolJsonCodec.decodeArguments(INPUT_JSON))
                .thenReturn(arguments());

        when(toolExecutionMapper.markRunning(
                TENANT_ID,
                TOOL_EXECUTION_ID,
                CONVERSATION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                "call-1",
                NOW
        )).thenReturn(affectedRows);

        assertThrows(
                IllegalStateException.class,
                () -> transactions.claim(context())
        );

        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldSucceedWithExactCallOrderAndRows() {
        stubConversationLock();
        stubExecutionLock(runningRow());
        stubRequestMessageLock();
        stubTicketCreation();
        stubMessageInsert(1);
        stubSequenceAdvance(1);
        stubMarkSucceeded(1);

        ExecuteCreateTicketToolResult result =
                transactions.succeed(claim());

        InOrder inOrder = inOrder(
                conversationMapper,
                toolExecutionMapper,
                createTicketAgentTool,
                messageMapper
        );

        inOrder.verify(conversationMapper)
                .findOwnedTurnForUpdate(
                        CONVERSATION_ID,
                        TENANT_ID,
                        REQUESTER_USER_ID
                );
        inOrder.verify(toolExecutionMapper)
                .findByTenantIdAndIdForUpdate(
                        TENANT_ID,
                        TOOL_EXECUTION_ID
                );
        inOrder.verify(messageMapper)
                .findCompletedToolCallRequestForUpdate(
                        REQUEST_MESSAGE_ID,
                        TENANT_ID,
                        CONVERSATION_ID
                );
        inOrder.verify(createTicketAgentTool)
                .execute(
                        eq(context()),
                        eq(arguments())
                );
        inOrder.verify(messageMapper, times(2)).insert(
                any(MessageRow.class)
        );
        inOrder.verify(conversationMapper)
                .advanceForToolContinuation(
                        CONVERSATION_ID,
                        TENANT_ID,
                        REQUESTER_USER_ID,
                        4L,
                        1,
                        NOW
                );
        inOrder.verify(toolExecutionMapper)
                .markSucceeded(
                        TENANT_ID,
                        TOOL_EXECUTION_ID,
                        CONVERSATION_ID,
                        AGENT_ID,
                        REQUEST_MESSAGE_ID,
                        "call-1",
                        TOOL_MESSAGE_ID,
                        OUTPUT_JSON,
                        TICKET_ID,
                        NOW,
                        0L
                );

        ArgumentCaptor<MessageRow> messageCaptor =
                ArgumentCaptor.forClass(MessageRow.class);

        verify(messageMapper, times(2)).insert(
                messageCaptor.capture()
        );

        List<MessageRow> messages = messageCaptor.getAllValues();

        MessageRow toolMessage = messages.get(0);

        assertEquals(TOOL_MESSAGE_ID, toolMessage.id());
        assertEquals(TENANT_ID, toolMessage.tenantId());
        assertEquals(
                CONVERSATION_ID,
                toolMessage.conversationId()
        );
        assertEquals(4L, toolMessage.sequenceNo());
        assertEquals(MessageRole.TOOL, toolMessage.role());
        assertEquals(OUTPUT_JSON, toolMessage.content());
        assertEquals(
                MessageContentType.JSON,
                toolMessage.contentType()
        );
        assertEquals(
                MessageStatus.COMPLETED,
                toolMessage.status()
        );
        assertEquals("{}", toolMessage.metadataJson());
        assertEquals(NOW, toolMessage.createdAt());

        MessageRow placeholder = messages.get(1);

        assertEquals(
                FINAL_ASSISTANT_MESSAGE_ID,
                placeholder.id()
        );
        assertEquals(TENANT_ID, placeholder.tenantId());
        assertEquals(
                CONVERSATION_ID,
                placeholder.conversationId()
        );
        assertEquals(5L, placeholder.sequenceNo());
        assertEquals(
                MessageRole.ASSISTANT,
                placeholder.role()
        );
        assertEquals("", placeholder.content());
        assertEquals(
                MessageContentType.TEXT,
                placeholder.contentType()
        );
        assertEquals(
                MessageStatus.CREATING,
                placeholder.status()
        );
        assertEquals(
                "gpt-5-mini",
                placeholder.modelName()
        );
        assertEquals("{}", placeholder.metadataJson());
        assertEquals(NOW, placeholder.createdAt());

        ArgumentCaptor<Map<String, ?>> metadataCaptor =
                ArgumentCaptor.forClass(Map.class);

        verify(metadataCodec, times(2)).encode(
                metadataCaptor.capture()
        );

        List<Map<String, ?>> encoded = metadataCaptor.getAllValues();

        Map<String, ?> toolMetadata = encoded.get(0);

        assertEquals(
                Set.of(
                        "toolExecutionId",
                        "toolCallId",
                        "toolName"
                ),
                toolMetadata.keySet()
        );
        assertEquals(
                Long.toString(TOOL_EXECUTION_ID),
                toolMetadata.get("toolExecutionId")
        );
        assertEquals(
                "call-1",
                toolMetadata.get("toolCallId")
        );
        assertEquals(
                "create_ticket",
                toolMetadata.get("toolName")
        );

        Map<String, ?> continuationMetadata = encoded.get(1);

        assertEquals(
                Set.of(
                        "messageKind",
                        "toolExecutionId",
                        "toolCallId",
                        "resultMessageId",
                        "conversationVersion"
                ),
                continuationMetadata.keySet()
        );
        assertEquals(
                "TOOL_CONTINUATION",
                continuationMetadata.get("messageKind")
        );
        assertEquals(
                Long.toString(TOOL_EXECUTION_ID),
                continuationMetadata.get("toolExecutionId")
        );
        assertEquals(
                "call-1",
                continuationMetadata.get("toolCallId")
        );
        assertEquals(
                Long.toString(TOOL_MESSAGE_ID),
                continuationMetadata.get("resultMessageId")
        );
        assertEquals(
                2,
                continuationMetadata
                        .get("conversationVersion")
        );

        assertEquals(
                TOOL_EXECUTION_ID,
                result.toolExecutionId()
        );
        assertEquals("9001", result.ticketId());
        assertEquals("TKT-A1", result.ticketNo());
        assertEquals(TicketStatus.OPEN, result.ticketStatus());
        assertEquals(TOOL_MESSAGE_ID, result.resultMessageId());
        assertEquals(4L, result.resultMessageSequenceNo());
        assertEquals(
                FINAL_ASSISTANT_MESSAGE_ID,
                result.assistantMessageId()
        );
        assertEquals(5L, result.assistantSequenceNo());
        assertEquals(2, result.conversationVersion());
        assertEquals(NOW, result.assistantPreparedAt());
        assertFalse(result.replayed());

        assertNotEquals(
                result.resultMessageId(),
                result.assistantMessageId()
        );
        assertTrue(result.resultMessageId() > 0);
        assertTrue(result.assistantMessageId() > 0);
    }

    @Test
    void shouldWriteSafeSuccessAudits() {
        stubConversationLock();
        stubExecutionLock(runningRow());
        stubRequestMessageLock();
        stubTicketCreation();
        stubMessageInsert(1);
        stubSequenceAdvance(1);
        stubMarkSucceeded(1);

        transactions.succeed(claim());

        ArgumentCaptor<AuditLogCommand> auditCaptor =
                ArgumentCaptor.forClass(AuditLogCommand.class);

        verify(auditLogWriter, times(3)).write(
                auditCaptor.capture()
        );

        List<AuditLogCommand> audits =
                auditCaptor.getAllValues();

        AuditLogCommand succeeded = audits.stream()
                .filter(command ->
                        "TOOL_EXECUTION_SUCCEEDED"
                                .equals(command.action())
                )
                .findFirst()
                .orElseThrow();

        assertEquals(
                AuditActorType.AGENT,
                succeeded.actorType()
        );
        assertEquals(AGENT_ID, succeeded.actorId());
        assertEquals(
                TOOL_EXECUTION_ID,
                succeeded.toolExecutionId()
        );
        assertEquals(AuditResult.SUCCESS, succeeded.result());

        Map<String, Object> afterData =
                (Map<String, Object>) succeeded.afterData();

        assertEquals("9001", afterData.get("ticketId"));
        assertEquals("TKT-A1", afterData.get("ticketNo"));

        assertFalse(afterData.containsKey("input"));
        assertFalse(afterData.containsKey("title"));
        assertFalse(afterData.containsKey("description"));

        AuditLogCommand continuation = audits.stream()
                .filter(command ->
                        "CONVERSATION_TOOL_CONTINUATION_PREPARED"
                                .equals(command.action())
                )
                .findFirst()
                .orElseThrow();

        assertEquals(
                AuditActorType.AGENT,
                continuation.actorType()
        );
        assertEquals(AGENT_ID, continuation.actorId());
        assertEquals(
                "MESSAGE",
                continuation.resourceType()
        );
        assertEquals(
                FINAL_ASSISTANT_MESSAGE_ID,
                continuation.resourceId()
        );
        assertEquals(
                TOOL_EXECUTION_ID,
                continuation.toolExecutionId()
        );
        assertEquals(
                AuditResult.SUCCESS,
                continuation.result()
        );

        Map<String, Object> continuationAfter =
                (Map<String, Object>) continuation.afterData();

        assertEquals(
                Set.of(
                        "conversationId",
                        "messageId",
                        "sequenceNo",
                        "status",
                        "toolExecutionId",
                        "resultMessageId",
                        "conversationVersion",
                        "preparedAt"
                ),
                continuationAfter.keySet()
        );
        assertEquals(
                Long.toString(CONVERSATION_ID),
                continuationAfter.get("conversationId")
        );
        assertEquals(
                Long.toString(FINAL_ASSISTANT_MESSAGE_ID),
                continuationAfter.get("messageId")
        );
        assertEquals(5L, continuationAfter.get("sequenceNo"));
        assertEquals(
                "CREATING",
                continuationAfter.get("status")
        );
        assertEquals(
                Long.toString(TOOL_EXECUTION_ID),
                continuationAfter.get("toolExecutionId")
        );
        assertEquals(
                Long.toString(TOOL_MESSAGE_ID),
                continuationAfter.get("resultMessageId")
        );
        assertEquals(
                2,
                continuationAfter.get("conversationVersion")
        );
        assertEquals(
                NOW.toString(),
                continuationAfter.get("preparedAt")
        );

        assertFalse(continuationAfter.containsKey("input"));
        assertFalse(continuationAfter.containsKey("title"));
        assertFalse(continuationAfter.containsKey("description"));
        assertFalse(continuationAfter.containsKey("output"));
    }

    @Test
    void shouldRejectSucceedWhenExecutionNotRunning() {
        stubConversationLock();
        stubExecutionLock(row(ToolExecutionStatus.PENDING));

        assertThrows(
                IllegalStateException.class,
                () -> transactions.succeed(claim())
        );

        verifyNoInteractions(createTicketAgentTool);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void shouldRejectSucceedWhenMessageInsertAffectsWrongRows(
            int affectedRows
    ) {
        stubConversationLock();
        stubExecutionLock(runningRow());
        stubRequestMessageLock();
        stubTicketCreation();
        stubMessageInsert(affectedRows);

        assertThrows(
                IllegalStateException.class,
                () -> transactions.succeed(claim())
        );

        verify(conversationMapper, never())
                .advanceForToolContinuation(
                        any(Long.class),
                        any(Long.class),
                        any(Long.class),
                        any(Long.class),
                        any(Integer.class),
                        any()
                );
        verify(toolExecutionMapper, never()).markSucceeded(
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(),
                any(Long.class),
                any(),
                any(Long.class),
                any(),
                any(Long.class)
        );
        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldRejectSucceedWhenSequenceAdvanceFails() {
        stubConversationLock();
        stubExecutionLock(runningRow());
        stubRequestMessageLock();
        stubTicketCreation();
        stubMessageInsert(1);
        stubSequenceAdvance(0);

        assertThrows(
                IllegalStateException.class,
                () -> transactions.succeed(claim())
        );

        verify(toolExecutionMapper, never()).markSucceeded(
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(),
                any(Long.class),
                any(),
                any(Long.class),
                any(),
                any(Long.class)
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void shouldRejectSucceedWhenMarkSucceededFails(
            int affectedRows
    ) {
        stubConversationLock();
        stubExecutionLock(runningRow());
        stubRequestMessageLock();
        stubTicketCreation();
        stubMessageInsert(1);
        stubSequenceAdvance(1);
        stubMarkSucceeded(affectedRows);

        assertThrows(
                IllegalStateException.class,
                () -> transactions.succeed(claim())
        );

        verifyNoInteractions(auditLogWriter);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void shouldRejectSucceedWhenSecondMessageInsertFails(
            int secondInsertRows
    ) {
        stubConversationLock();
        stubExecutionLock(runningRow());
        stubRequestMessageLock();
        stubTicketCreation();
        stubMessageInserts(1, secondInsertRows);

        assertThrows(
                IllegalStateException.class,
                () -> transactions.succeed(claim())
        );

        verify(conversationMapper, never())
                .advanceForToolContinuation(
                        any(Long.class),
                        any(Long.class),
                        any(Long.class),
                        any(Long.class),
                        any(Integer.class),
                        any()
                );
        verify(toolExecutionMapper, never()).markSucceeded(
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(),
                any(Long.class),
                any(),
                any(Long.class),
                any(),
                any(Long.class)
        );
        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldRejectSucceedWhenSequenceIsExhausted() {
        stubConversationLockWith(
                Long.MAX_VALUE,
                1
        );
        stubExecutionLock(runningRow());

        when(messageMapper
                .findCompletedToolCallRequestForUpdate(
                        REQUEST_MESSAGE_ID,
                        TENANT_ID,
                        CONVERSATION_ID
                )).thenReturn(Optional.of(
                        requestMessageRow(
                                Long.MAX_VALUE - 1
                        )
                ));

        assertThrows(
                IllegalStateException.class,
                () -> transactions.succeed(claim())
        );

        verifyNoInteractions(createTicketAgentTool);
        verifyNoInteractions(idGenerator);
        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldRejectSucceedWhenVersionIsExhausted() {
        stubConversationLockWith(
                4L,
                Integer.MAX_VALUE
        );
        stubExecutionLock(runningRow());
        stubRequestMessageLock();

        assertThrows(
                IllegalStateException.class,
                () -> transactions.succeed(claim())
        );

        verifyNoInteractions(createTicketAgentTool);
        verifyNoInteractions(idGenerator);
        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldRejectSucceedWhenGeneratedMessageIdsAreNotDistinct() {
        stubConversationLock();
        stubExecutionLock(runningRow());
        stubRequestMessageLock();

        when(createTicketAgentTool.execute(
                any(AgentToolExecutionContext.class),
                any(CreateTicketToolArguments.class)
        )).thenReturn(new CreateTicketResponse(
                Long.toString(TICKET_ID),
                "TKT-A1",
                TicketStatus.OPEN
        ));

        when(idGenerator.nextId())
                .thenReturn(TOOL_MESSAGE_ID)
                .thenReturn(TOOL_MESSAGE_ID);

        assertThrows(
                IllegalStateException.class,
                () -> transactions.succeed(claim())
        );

        verify(messageMapper, never()).insert(
                any(MessageRow.class)
        );
        verify(conversationMapper, never())
                .advanceForToolContinuation(
                        any(Long.class),
                        any(Long.class),
                        any(Long.class),
                        any(Long.class),
                        any(Integer.class),
                        any()
                );
        verify(toolExecutionMapper, never()).markSucceeded(
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(),
                any(Long.class),
                any(),
                any(Long.class),
                any(),
                any(Long.class)
        );
        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldPropagateAuditFailureInSucceed() {
        stubConversationLock();
        stubExecutionLock(runningRow());
        stubRequestMessageLock();
        stubTicketCreation();
        stubMessageInsert(1);
        stubSequenceAdvance(1);
        stubMarkSucceeded(1);

        IllegalStateException failure =
                new IllegalStateException("audit boom");

        doThrow(failure)
                .when(auditLogWriter)
                .write(any(AuditLogCommand.class));

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> transactions.succeed(claim())
                );

        assertSame(failure, thrown);

        verify(toolExecutionMapper).markSucceeded(
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(),
                any(Long.class),
                any(),
                any(Long.class),
                any(),
                any(Long.class)
        );
    }

    @Test
    void shouldRejectSucceedWhenRequestMessageMissing() {
        stubConversationLock();
        stubExecutionLock(runningRow());

        when(messageMapper
                .findCompletedToolCallRequestForUpdate(
                        REQUEST_MESSAGE_ID,
                        TENANT_ID,
                        CONVERSATION_ID
                )).thenReturn(Optional.empty());

        assertThrows(
                IllegalStateException.class,
                () -> transactions.succeed(claim())
        );

        verifyNoInteractions(createTicketAgentTool);
        verify(messageMapper, never()).insert(
                any(MessageRow.class)
        );
        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldRejectSucceedWhenSequenceDoesNotFollowRequest() {
        stubConversationLock();
        stubExecutionLock(runningRow());

        when(messageMapper
                .findCompletedToolCallRequestForUpdate(
                        REQUEST_MESSAGE_ID,
                        TENANT_ID,
                        CONVERSATION_ID
                )).thenReturn(Optional.of(
                        requestMessageRow(2L)
                ));

        assertThrows(
                IllegalStateException.class,
                () -> transactions.succeed(claim())
        );

        verifyNoInteractions(createTicketAgentTool);
        verify(messageMapper, never()).insert(
                any(MessageRow.class)
        );
        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldFailPendingExecutionWithSafeFields() {
        stubConversationLock();
        stubExecutionLock(row(ToolExecutionStatus.PENDING));

        when(toolExecutionMapper.markFailed(
                TENANT_ID,
                TOOL_EXECUTION_ID,
                CONVERSATION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                "call-1",
                ToolExecutionStatus.PENDING,
                "ERR",
                "safe",
                NOW,
                0L
        )).thenReturn(1);

        transactions.fail(context(), failure("ERR", "safe", NOW));

        ArgumentCaptor<AuditLogCommand> auditCaptor =
                ArgumentCaptor.forClass(AuditLogCommand.class);

        verify(auditLogWriter).write(auditCaptor.capture());

        AuditLogCommand audit = auditCaptor.getValue();

        assertEquals(
                "TOOL_EXECUTION_FAILED",
                audit.action()
        );
        assertEquals(AuditResult.FAILURE, audit.result());
        assertEquals("ERR", audit.errorCode());
        assertEquals("safe", audit.errorMessage());

        Map<String, Object> afterData =
                (Map<String, Object>) audit.afterData();

        assertFalse(
                afterData.values().stream()
                        .anyMatch(value ->
                                String.valueOf(value)
                                        .contains("secret")
                        )
        );
        assertFalse(audit.errorMessage().contains("secret"));
        assertFalse(audit.errorCode().contains("secret"));
    }

    @Test
    void shouldFailRunningExecutionWithDuration() {
        stubConversationLock();
        stubExecutionLock(runningRow());

        when(toolExecutionMapper.markFailed(
                TENANT_ID,
                TOOL_EXECUTION_ID,
                CONVERSATION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                "call-1",
                ToolExecutionStatus.RUNNING,
                "ERR",
                "safe",
                LATER,
                2000L
        )).thenReturn(1);

        transactions.fail(context(), failure("ERR", "safe", LATER));

        verify(toolExecutionMapper).markFailed(
                TENANT_ID,
                TOOL_EXECUTION_ID,
                CONVERSATION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                "call-1",
                ToolExecutionStatus.RUNNING,
                "ERR",
                "safe",
                LATER,
                2000L
        );
    }

    @ParameterizedTest
    @MethodSource("nonOverwritableStatuses")
    void shouldNeverOverwriteExecutions(
            ToolExecutionStatus status
    ) {
        stubConversationLock();
        stubExecutionLock(row(status));

        transactions.fail(context(), failure("ERR", "safe", NOW));

        verify(toolExecutionMapper, never()).markFailed(
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(Long.class),
                any(),
                any(ToolExecutionStatus.class),
                any(),
                any(),
                any(),
                any(Long.class)
        );
        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldRejectMarkFailedUnexpectedRows() {
        stubConversationLock();
        stubExecutionLock(row(ToolExecutionStatus.PENDING));

        when(toolExecutionMapper.markFailed(
                TENANT_ID,
                TOOL_EXECUTION_ID,
                CONVERSATION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                "call-1",
                ToolExecutionStatus.PENDING,
                "ERR",
                "safe",
                NOW,
                0L
        )).thenReturn(0);

        assertThrows(
                IllegalStateException.class,
                () -> transactions.fail(
                        context(),
                        failure("ERR", "safe", NOW)
                )
        );

        verifyNoInteractions(auditLogWriter);
    }

    private void stubConversationLock() {
        stubConversationLockWithStatus(
                ConversationStatus.ACTIVE
        );
    }

    private void stubConversationLockWithStatus(
            ConversationStatus status
    ) {
        when(conversationMapper.findOwnedTurnForUpdate(
                CONVERSATION_ID,
                TENANT_ID,
                REQUESTER_USER_ID
        )).thenReturn(Optional.of(stateRow(status)));
    }

    private void stubConversationLockWith(
            long nextMessageSequence,
            int version
    ) {
        when(conversationMapper.findOwnedTurnForUpdate(
                CONVERSATION_ID,
                TENANT_ID,
                REQUESTER_USER_ID
        )).thenReturn(Optional.of(
                new ConversationTurnStateRow(
                        CONVERSATION_ID,
                        TENANT_ID,
                        REQUESTER_USER_ID,
                        AGENT_ID,
                        ConversationStatus.ACTIVE,
                        nextMessageSequence,
                        version
                )
        ));
    }

    private void stubExecutionLock(ToolExecutionRow row) {
        when(toolExecutionMapper
                .findByTenantIdAndIdForUpdate(
                        TENANT_ID,
                        TOOL_EXECUTION_ID
                )).thenReturn(Optional.of(row));
    }

    private void stubPendingClaimSuccesses() {
        when(toolExecutionMapper.markRunning(
                TENANT_ID,
                TOOL_EXECUTION_ID,
                CONVERSATION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                "call-1",
                NOW
        )).thenReturn(1);
    }

    private void stubRequestMessageLock() {
        when(messageMapper
                .findCompletedToolCallRequestForUpdate(
                        REQUEST_MESSAGE_ID,
                        TENANT_ID,
                        CONVERSATION_ID
                )).thenReturn(Optional.of(
                        requestMessageRow()
                ));
    }

    private void stubReplayMessageReads() {
        when(messageMapper.findOwnedMessageByIdForUpdate(
                TOOL_MESSAGE_ID,
                TENANT_ID,
                CONVERSATION_ID
        )).thenReturn(Optional.of(toolMessageRow()));

        when(messageMapper
                .findOwnedMessageBySequenceForUpdate(
                        TENANT_ID,
                        CONVERSATION_ID,
                        4L
                )).thenReturn(Optional.of(
                        assistantMessageRow()
                ));
    }

    private static ToolCallRequestMessageRow requestMessageRow() {
        return requestMessageRow(3L);
    }

    private static ToolCallRequestMessageRow requestMessageRow(
            long sequenceNo
    ) {
        return new ToolCallRequestMessageRow(
                REQUEST_MESSAGE_ID,
                TENANT_ID,
                CONVERSATION_ID,
                sequenceNo,
                MessageRole.ASSISTANT,
                INPUT_JSON,
                MessageContentType.JSON,
                MessageStatus.COMPLETED,
                "gpt-5-mini",
                "{}",
                NOW
        );
    }

    private static ToolCallRequestMessageRow toolMessageRow() {
        return new ToolCallRequestMessageRow(
                TOOL_MESSAGE_ID,
                TENANT_ID,
                CONVERSATION_ID,
                3L,
                MessageRole.TOOL,
                OUTPUT_JSON,
                MessageContentType.JSON,
                MessageStatus.COMPLETED,
                null,
                "{}",
                NOW
        );
    }

    private static ToolCallRequestMessageRow assistantMessageRow() {
        return new ToolCallRequestMessageRow(
                FINAL_ASSISTANT_MESSAGE_ID,
                TENANT_ID,
                CONVERSATION_ID,
                4L,
                MessageRole.ASSISTANT,
                "",
                MessageContentType.TEXT,
                MessageStatus.CREATING,
                "gpt-5-mini",
                "{}",
                NOW
        );
    }

    private void stubTicketCreation() {
        when(createTicketAgentTool.execute(
                any(AgentToolExecutionContext.class),
                any(CreateTicketToolArguments.class)
        )).thenReturn(new CreateTicketResponse(
                Long.toString(TICKET_ID),
                "TKT-A1",
                TicketStatus.OPEN
        ));

        when(idGenerator.nextId())
                .thenReturn(TOOL_MESSAGE_ID)
                .thenReturn(FINAL_ASSISTANT_MESSAGE_ID);

        when(ticketToolJsonCodec.encodeOutput(
                any(CreateTicketToolOutput.class)
        )).thenReturn(OUTPUT_JSON);

        when(metadataCodec.encode(any(Map.class)))
                .thenReturn("{}");
    }

    private void stubMessageInsert(int affectedRows) {
        when(messageMapper.insert(any()))
                .thenReturn(affectedRows);
    }

    private void stubMessageInserts(
            int first,
            int second
    ) {
        when(messageMapper.insert(any()))
                .thenReturn(first)
                .thenReturn(second);
    }

    private void stubSequenceAdvance(int affectedRows) {
        stubSequenceAdvance(4L, 1, affectedRows);
    }

    private void stubSequenceAdvance(
            long nextSequence,
            int version,
            int affectedRows
    ) {
        when(conversationMapper.advanceForToolContinuation(
                CONVERSATION_ID,
                TENANT_ID,
                REQUESTER_USER_ID,
                nextSequence,
                version,
                NOW
        )).thenReturn(affectedRows);
    }

    private void stubMarkSucceeded(int affectedRows) {
        when(toolExecutionMapper.markSucceeded(
                TENANT_ID,
                TOOL_EXECUTION_ID,
                CONVERSATION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                "call-1",
                TOOL_MESSAGE_ID,
                OUTPUT_JSON,
                TICKET_ID,
                NOW,
                0L
        )).thenReturn(affectedRows);
    }

    private static ConversationTurnStateRow stateRow() {
        return stateRow(ConversationStatus.ACTIVE);
    }

    private static ConversationTurnStateRow stateRow(
            ConversationStatus status
    ) {
        return new ConversationTurnStateRow(
                CONVERSATION_ID,
                TENANT_ID,
                REQUESTER_USER_ID,
                AGENT_ID,
                status,
                4L,
                1
        );
    }

    private static AgentToolExecutionContext context() {
        return new AgentToolExecutionContext(
                TENANT_ID,
                REQUESTER_USER_ID,
                CONVERSATION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                TOOL_EXECUTION_ID,
                "call-1"
        );
    }

    private static ClaimedCreateTicketToolExecution claim() {
        return ClaimedCreateTicketToolExecution.fresh(
                context(),
                arguments(),
                NOW
        );
    }

    private static CreateTicketToolArguments arguments() {
        return new CreateTicketToolArguments(
                "Server down",
                "It is down",
                TicketPriority.HIGH
        );
    }

    private static CreateTicketToolOutput output() {
        return new CreateTicketToolOutput(
                "9001",
                "TKT-A1",
                TicketStatus.OPEN
        );
    }

    private static CreateTicketToolFailure failure(
            String errorCode,
            String safeMessage,
            Instant failedAt
    ) {
        return new CreateTicketToolFailure(
                errorCode,
                safeMessage,
                failedAt
        );
    }

    private static ToolExecutionRow row(ToolExecutionStatus status) {
        return new ToolExecutionRow(
                TOOL_EXECUTION_ID,
                TENANT_ID,
                CONVERSATION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                null,
                "call-1",
                "create_ticket",
                IDEMPOTENCY_KEY,
                INPUT_JSON,
                null,
                status,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                NOW,
                NOW
        );
    }

    private static ToolExecutionRow runningRow() {
        return new ToolExecutionRow(
                TOOL_EXECUTION_ID,
                TENANT_ID,
                CONVERSATION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                null,
                "call-1",
                "create_ticket",
                IDEMPOTENCY_KEY,
                INPUT_JSON,
                null,
                ToolExecutionStatus.RUNNING,
                false,
                null,
                null,
                null,
                null,
                null,
                NOW,
                null,
                null,
                NOW,
                NOW
        );
    }

    private static ToolExecutionRow succeededRow() {
        return new ToolExecutionRow(
                TOOL_EXECUTION_ID,
                TENANT_ID,
                CONVERSATION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                TOOL_MESSAGE_ID,
                "call-1",
                "create_ticket",
                IDEMPOTENCY_KEY,
                INPUT_JSON,
                OUTPUT_JSON,
                ToolExecutionStatus.SUCCEEDED,
                false,
                "TICKET",
                TICKET_ID,
                null,
                null,
                null,
                NOW,
                NOW,
                0L,
                NOW,
                NOW
        );
    }

    private static ToolCallRequestMessageRow withToolMessage(
            MessageRole role,
            MessageStatus status,
            MessageContentType contentType,
            String content,
            long sequenceNo
    ) {
        return new ToolCallRequestMessageRow(
                TOOL_MESSAGE_ID,
                TENANT_ID,
                CONVERSATION_ID,
                sequenceNo,
                role,
                content,
                contentType,
                status,
                null,
                "{}",
                NOW
        );
    }

    private static ToolCallRequestMessageRow withAssistantMessage(
            long id,
            MessageRole role,
            MessageContentType contentType,
            long sequenceNo,
            String modelName,
            MessageStatus status
    ) {
        return new ToolCallRequestMessageRow(
                id,
                TENANT_ID,
                CONVERSATION_ID,
                sequenceNo,
                role,
                "",
                contentType,
                status,
                modelName,
                "{}",
                NOW
        );
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments>
    invalidReplayMessages() {
        ToolCallRequestMessageRow tool = toolMessageRow();
        ToolCallRequestMessageRow assistant =
                assistantMessageRow();

        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "tool id",
                        new ToolCallRequestMessageRow(
                                TOOL_MESSAGE_ID + 999L,
                                TENANT_ID,
                                CONVERSATION_ID,
                                3L,
                                MessageRole.TOOL,
                                OUTPUT_JSON,
                                MessageContentType.JSON,
                                MessageStatus.COMPLETED,
                                null,
                                "{}",
                                NOW
                        ),
                        assistant
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "tool role",
                        withToolMessage(
                                MessageRole.ASSISTANT,
                                MessageStatus.COMPLETED,
                                MessageContentType.JSON,
                                OUTPUT_JSON,
                                3L
                        ),
                        assistant
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "tool status",
                        withToolMessage(
                                MessageRole.TOOL,
                                MessageStatus.CREATING,
                                MessageContentType.JSON,
                                OUTPUT_JSON,
                                3L
                        ),
                        assistant
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "tool content type",
                        withToolMessage(
                                MessageRole.TOOL,
                                MessageStatus.COMPLETED,
                                MessageContentType.TEXT,
                                OUTPUT_JSON,
                                3L
                        ),
                        assistant
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "tool content empty",
                        withToolMessage(
                                MessageRole.TOOL,
                                MessageStatus.COMPLETED,
                                MessageContentType.JSON,
                                "",
                                3L
                        ),
                        assistant
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "tool content null",
                        withToolMessage(
                                MessageRole.TOOL,
                                MessageStatus.COMPLETED,
                                MessageContentType.JSON,
                                null,
                                3L
                        ),
                        assistant
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "tool sequence",
                        withToolMessage(
                                MessageRole.TOOL,
                                MessageStatus.COMPLETED,
                                MessageContentType.JSON,
                                OUTPUT_JSON,
                                0L
                        ),
                        assistant
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "assistant id",
                        tool,
                        withAssistantMessage(
                                0L,
                                MessageRole.ASSISTANT,
                                MessageContentType.TEXT,
                                4L,
                                "gpt-5-mini",
                                MessageStatus.CREATING
                        )
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "assistant id duplicate",
                        tool,
                        withAssistantMessage(
                                TOOL_MESSAGE_ID,
                                MessageRole.ASSISTANT,
                                MessageContentType.TEXT,
                                4L,
                                "gpt-5-mini",
                                MessageStatus.CREATING
                        )
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "assistant role",
                        tool,
                        withAssistantMessage(
                                FINAL_ASSISTANT_MESSAGE_ID,
                                MessageRole.TOOL,
                                MessageContentType.TEXT,
                                4L,
                                "gpt-5-mini",
                                MessageStatus.CREATING
                        )
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "assistant content type",
                        tool,
                        withAssistantMessage(
                                FINAL_ASSISTANT_MESSAGE_ID,
                                MessageRole.ASSISTANT,
                                MessageContentType.JSON,
                                4L,
                                "gpt-5-mini",
                                MessageStatus.CREATING
                        )
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "assistant sequence",
                        tool,
                        withAssistantMessage(
                                FINAL_ASSISTANT_MESSAGE_ID,
                                MessageRole.ASSISTANT,
                                MessageContentType.TEXT,
                                5L,
                                "gpt-5-mini",
                                MessageStatus.CREATING
                        )
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "assistant model name null",
                        tool,
                        withAssistantMessage(
                                FINAL_ASSISTANT_MESSAGE_ID,
                                MessageRole.ASSISTANT,
                                MessageContentType.TEXT,
                                4L,
                                null,
                                MessageStatus.CREATING
                        )
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "assistant model name empty",
                        tool,
                        withAssistantMessage(
                                FINAL_ASSISTANT_MESSAGE_ID,
                                MessageRole.ASSISTANT,
                                MessageContentType.TEXT,
                                4L,
                                "",
                                MessageStatus.CREATING
                        )
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "assistant status",
                        tool,
                        withAssistantMessage(
                                FINAL_ASSISTANT_MESSAGE_ID,
                                MessageRole.ASSISTANT,
                                MessageContentType.TEXT,
                                4L,
                                "gpt-5-mini",
                                null
                        )
                )
        );
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments>
    terminalStatuses() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        ToolExecutionStatus.FAILED
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        ToolExecutionStatus.CANCELLED
                )
        );
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments>
    nonOverwritableStatuses() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        ToolExecutionStatus.SUCCEEDED
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        ToolExecutionStatus.FAILED
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        ToolExecutionStatus.CANCELLED
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        ToolExecutionStatus.WAITING_APPROVAL
                )
        );
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments>
    mismatchedExecutions() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        new ToolExecutionRow(
                                TOOL_EXECUTION_ID,
                                TENANT_ID,
                                CONVERSATION_ID,
                                AGENT_ID + 1,
                                REQUEST_MESSAGE_ID,
                                null,
                                "call-1",
                                "create_ticket",
                                IDEMPOTENCY_KEY,
                                INPUT_JSON,
                                null,
                                ToolExecutionStatus.PENDING,
                                false,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                NOW,
                                NOW
                        )
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        new ToolExecutionRow(
                                TOOL_EXECUTION_ID,
                                TENANT_ID,
                                CONVERSATION_ID,
                                AGENT_ID,
                                REQUEST_MESSAGE_ID,
                                null,
                                "call-1",
                                "other_tool",
                                IDEMPOTENCY_KEY,
                                INPUT_JSON,
                                null,
                                ToolExecutionStatus.PENDING,
                                false,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                NOW,
                                NOW
                        )
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        new ToolExecutionRow(
                                TOOL_EXECUTION_ID,
                                TENANT_ID,
                                CONVERSATION_ID,
                                AGENT_ID,
                                REQUEST_MESSAGE_ID,
                                null,
                                "call-other",
                                "create_ticket",
                                IDEMPOTENCY_KEY,
                                INPUT_JSON,
                                null,
                                ToolExecutionStatus.PENDING,
                                false,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                NOW,
                                NOW
                        )
                )
        );
    }
}
