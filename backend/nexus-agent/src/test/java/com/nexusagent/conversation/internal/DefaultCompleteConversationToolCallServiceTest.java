package com.nexusagent.conversation.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.agent.api.ActiveAgentRuntime;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.conversation.internal.persistence.MessageMapper;
import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatModelRequest;
import com.nexusagent.model.api.ChatModelToolCall;
import com.nexusagent.model.api.ChatTokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultCompleteConversationToolCallServiceTest {

    private static final long TENANT_ID = 202L;
    private static final long USER_ID = 101L;
    private static final long CONVERSATION_ID = 901L;
    private static final long AGENT_ID = 500L;
    private static final long USER_MESSAGE_ID = 1001L;
    private static final long ASSISTANT_MESSAGE_ID = 1002L;
    private static final long TOOL_EXECUTION_ID = 7001L;

    private static final Instant PREPARED_AT =
            Instant.parse("2026-08-13T10:15:30.123Z");

    private static final Instant COMPLETED_AT =
            Instant.parse("2026-08-13T10:15:32.123Z");

    private static final ChatTokenUsage USAGE =
            new ChatTokenUsage(5, 7);

    private static final String ENCODED_TOOL_CALL =
            "{\"id\":\"call_123\","
                    + "\"name\":\"create_ticket\","
                    + "\"arguments\":{\"title\":"
                    + "\"Payment failed\"}}";

    private static final String METADATA_JSON = "{}";

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private ConversationToolCallMessageJsonCodec toolCallJsonCodec;

    @Mock
    private ConversationTurnMetadataJsonCodec metadataCodec;

    @Mock
    private AuditLogWriter auditLogWriter;

    private DefaultCompleteConversationToolCallService service;

    @BeforeEach
    void setUp() {
        service = new DefaultCompleteConversationToolCallService(
                messageMapper,
                toolCallJsonCodec,
                metadataCodec,
                auditLogWriter,
                Clock.fixed(COMPLETED_AT, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldCompleteToolCallWithExactMapperParameters() {
        ChatModelToolCall toolCall = toolCall();

        stubSuccessfulCompletion(toolCall);

        CompletedConversationToolCall completed =
                service.complete(
                        prepared(),
                        toolCall,
                        USAGE,
                        TOOL_EXECUTION_ID
                );

        verify(messageMapper).completeAssistantToolCallMessage(
                ASSISTANT_MESSAGE_ID,
                TENANT_ID,
                CONVERSATION_ID,
                AGENT_ID,
                2L,
                ENCODED_TOOL_CALL,
                "gpt-5-mini",
                5,
                7,
                METADATA_JSON,
                TOOL_EXECUTION_ID,
                "call_123",
                "create_ticket"
        );

        ArgumentCaptor<Map<String, ?>> metadataCaptor =
                ArgumentCaptor.forClass(Map.class);

        verify(metadataCodec).encode(metadataCaptor.capture());

        Map<String, ?> metadata = metadataCaptor.getValue();

        assertEquals("TOOL_CALLS", metadata.get("messageKind"));
        assertEquals("OPENAI", metadata.get("provider"));
        assertEquals(
                "TOOL_CALLS",
                metadata.get("finishReason")
        );
        assertEquals(
                "call_123",
                metadata.get("toolCallId")
        );
        assertEquals(
                "create_ticket",
                metadata.get("toolName")
        );
        assertEquals(
                Long.toString(TOOL_EXECUTION_ID),
                metadata.get("toolExecutionId")
        );
        assertEquals(
                COMPLETED_AT.toString(),
                metadata.get("completedAt")
        );
        assertEquals(7, metadata.size());

        assertEquals(
                TENANT_ID,
                completed.tenantId()
        );
        assertEquals(USER_ID, completed.userId());
        assertEquals(
                CONVERSATION_ID,
                completed.conversationId()
        );
        assertEquals(AGENT_ID, completed.agentId());
        assertEquals(
                ASSISTANT_MESSAGE_ID,
                completed.assistantMessageId()
        );
        assertEquals(2L, completed.assistantSequenceNo());
        assertSame(toolCall, completed.toolCall());
        assertEquals(
                TOOL_EXECUTION_ID,
                completed.toolExecutionId()
        );
        assertEquals("gpt-5-mini", completed.modelName());
        assertSame(USAGE, completed.usage());
        assertEquals(PREPARED_AT, completed.createdAt());
        assertEquals(COMPLETED_AT, completed.completedAt());
    }

    @Test
    void shouldWriteSafeAgentAudit() {
        ChatModelToolCall toolCall = toolCall();

        stubSuccessfulCompletion(toolCall);

        service.complete(
                prepared(),
                toolCall,
                USAGE,
                TOOL_EXECUTION_ID
        );

        ArgumentCaptor<AuditLogCommand> auditCaptor =
                ArgumentCaptor.forClass(AuditLogCommand.class);

        verify(auditLogWriter).write(auditCaptor.capture());

        AuditLogCommand audit = auditCaptor.getValue();

        assertEquals(TENANT_ID, audit.tenantId());
        assertEquals(
                AuditActorType.AGENT,
                audit.actorType()
        );
        assertEquals(AGENT_ID, audit.actorId());
        assertEquals(
                "CONVERSATION_TOOL_CALL_COMPLETED",
                audit.action()
        );
        assertEquals("MESSAGE", audit.resourceType());
        assertEquals(
                ASSISTANT_MESSAGE_ID,
                audit.resourceId()
        );
        assertEquals(
                TOOL_EXECUTION_ID,
                audit.toolExecutionId()
        );
        assertEquals(AuditResult.SUCCESS, audit.result());

        Map<String, Object> afterData =
                (Map<String, Object>) audit.afterData();

        assertEquals(
                Long.toString(CONVERSATION_ID),
                afterData.get("conversationId")
        );
        assertEquals(
                Long.toString(ASSISTANT_MESSAGE_ID),
                afterData.get("messageId")
        );
        assertEquals(2L, afterData.get("sequenceNo"));
        assertEquals("COMPLETED", afterData.get("status"));
        assertEquals(
                Long.toString(TOOL_EXECUTION_ID),
                afterData.get("toolExecutionId")
        );
        assertEquals("call_123", afterData.get("toolCallId"));
        assertEquals(
                "create_ticket",
                afterData.get("toolName")
        );
        assertEquals(5, afterData.get("promptTokens"));
        assertEquals(7, afterData.get("completionTokens"));
        assertEquals(
                COMPLETED_AT.toString(),
                afterData.get("completedAt")
        );

        String auditText = String.valueOf(
                audit.beforeData()
        ) + String.valueOf(audit.afterData());

        org.junit.jupiter.api.Assertions.assertFalse(
                auditText.contains("Payment failed")
        );
        org.junit.jupiter.api.Assertions.assertFalse(
                auditText.contains("title")
        );
        org.junit.jupiter.api.Assertions.assertFalse(
                auditText.contains("description")
        );
        org.junit.jupiter.api.Assertions.assertFalse(
                auditText.contains("priority")
        );
        org.junit.jupiter.api.Assertions.assertFalse(
                auditText.contains("arguments")
        );
        org.junit.jupiter.api.Assertions.assertFalse(
                auditText.contains("systemPrompt")
        );
        org.junit.jupiter.api.Assertions.assertFalse(
                auditText.contains("modelConfig")
        );
    }

    @Test
    void shouldRejectNonCreateTicketToolWithoutSideEffects() {
        ChatModelToolCall foreignTool = new ChatModelToolCall(
                "call_123",
                "other_tool",
                toolCall().arguments()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.complete(
                        prepared(),
                        foreignTool,
                        USAGE,
                        TOOL_EXECUTION_ID
                )
        );

        verifyNoInteractions(
                messageMapper,
                toolCallJsonCodec,
                metadataCodec,
                auditLogWriter
        );
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void shouldRejectNonPositiveToolExecutionId(
            long toolExecutionId
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.complete(
                        prepared(),
                        toolCall(),
                        USAGE,
                        toolExecutionId
                )
        );

        verifyNoInteractions(
                messageMapper,
                toolCallJsonCodec,
                metadataCodec,
                auditLogWriter
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void shouldRejectUnexpectedMapperRowsWithoutAuditing(
            int affectedRows
    ) {
        ChatModelToolCall toolCall = toolCall();

        when(toolCallJsonCodec.encode(toolCall))
                .thenReturn(ENCODED_TOOL_CALL);
        when(metadataCodec.encode(any(Map.class)))
                .thenReturn(METADATA_JSON);
        when(messageMapper.completeAssistantToolCallMessage(
                ASSISTANT_MESSAGE_ID,
                TENANT_ID,
                CONVERSATION_ID,
                AGENT_ID,
                2L,
                ENCODED_TOOL_CALL,
                "gpt-5-mini",
                5,
                7,
                METADATA_JSON,
                TOOL_EXECUTION_ID,
                "call_123",
                "create_ticket"
        )).thenReturn(affectedRows);

        assertThrows(
                IllegalStateException.class,
                () -> service.complete(
                        prepared(),
                        toolCall,
                        USAGE,
                        TOOL_EXECUTION_ID
                )
        );

        verify(auditLogWriter, never()).write(
                any(AuditLogCommand.class)
        );
    }

    @Test
    void shouldNotCallMapperWhenCodecFails() {
        ChatModelToolCall toolCall = toolCall();

        when(toolCallJsonCodec.encode(toolCall))
                .thenThrow(new IllegalStateException(
                        "codec boom"
                ));

        assertThrows(
                IllegalStateException.class,
                () -> service.complete(
                        prepared(),
                        toolCall,
                        USAGE,
                        TOOL_EXECUTION_ID
                )
        );

        verify(messageMapper, never())
                .completeAssistantToolCallMessage(
                        any(Long.class),
                        any(Long.class),
                        any(Long.class),
                        any(Long.class),
                        any(Long.class),
                        any(),
                        any(),
                        any(Integer.class),
                        any(Integer.class),
                        any(),
                        any(Long.class),
                        any(),
                        any()
                );
        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldPropagateAuditFailure() {
        ChatModelToolCall toolCall = toolCall();

        stubSuccessfulCompletion(toolCall);

        IllegalStateException failure =
                new IllegalStateException("audit boom");

        doThrow(failure)
                .when(auditLogWriter)
                .write(any(AuditLogCommand.class));

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.complete(
                                prepared(),
                                toolCall,
                                USAGE,
                                TOOL_EXECUTION_ID
                        )
                );

        assertSame(failure, thrown);
    }

    @Test
    void shouldRejectCompletionBeforePreparation() {
        service = new DefaultCompleteConversationToolCallService(
                messageMapper,
                toolCallJsonCodec,
                metadataCodec,
                auditLogWriter,
                Clock.fixed(
                        PREPARED_AT.minusSeconds(1),
                        ZoneOffset.UTC
                )
        );

        assertThrows(
                IllegalStateException.class,
                () -> service.complete(
                        prepared(),
                        toolCall(),
                        USAGE,
                        TOOL_EXECUTION_ID
                )
        );

        verifyNoInteractions(
                messageMapper,
                toolCallJsonCodec,
                metadataCodec,
                auditLogWriter
        );
    }

    private void stubSuccessfulCompletion(
            ChatModelToolCall toolCall
    ) {
        when(toolCallJsonCodec.encode(toolCall))
                .thenReturn(ENCODED_TOOL_CALL);
        when(metadataCodec.encode(any(Map.class)))
                .thenReturn(METADATA_JSON);
        when(messageMapper.completeAssistantToolCallMessage(
                ASSISTANT_MESSAGE_ID,
                TENANT_ID,
                CONVERSATION_ID,
                AGENT_ID,
                2L,
                ENCODED_TOOL_CALL,
                "gpt-5-mini",
                5,
                7,
                METADATA_JSON,
                TOOL_EXECUTION_ID,
                "call_123",
                "create_ticket"
        )).thenReturn(1);
    }

    private static ChatModelToolCall toolCall() {
        try {
            return new ChatModelToolCall(
                    "call_123",
                    "create_ticket",
                    new ObjectMapper().readTree(
                            """
                            {
                                "title": "Payment failed",
                                "description": "Cannot pay",
                                "priority": "HIGH"
                            }
                            """
                    )
            );
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static PreparedConversationTurn prepared() {
        return new PreparedConversationTurn(
                TENANT_ID,
                USER_ID,
                CONVERSATION_ID,
                new ActiveAgentRuntime(
                        AGENT_ID,
                        TENANT_ID,
                        "support-agent",
                        "system-prompt-sensitive-value",
                        AgentModelProvider.OPENAI,
                        "gpt-5-mini",
                        null
                ),
                USER_MESSAGE_ID,
                1L,
                ASSISTANT_MESSAGE_ID,
                2L,
                1,
                PREPARED_AT,
                new ChatModelRequest(
                        "gpt-5-mini",
                        "system-prompt-sensitive-value",
                        com.nexusagent.model.api.ChatModelOptions
                                .defaults(),
                        java.util.List.of(
                                com.nexusagent.model.api
                                        .ChatModelMessage
                                        .user("Hello")
                        ),
                        java.util.List.of()
                )
        );
    }
}
