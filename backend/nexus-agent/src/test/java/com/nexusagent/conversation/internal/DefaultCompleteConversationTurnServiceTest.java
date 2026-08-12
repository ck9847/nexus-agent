package com.nexusagent.conversation.internal;

import com.nexusagent.agent.api.ActiveAgentRuntime;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.conversation.internal.persistence.MessageMapper;
import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatModelMessage;
import com.nexusagent.model.api.ChatModelOptions;
import com.nexusagent.model.api.ChatModelRequest;
import com.nexusagent.model.api.ChatTokenUsage;
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

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultCompleteConversationTurnServiceTest {

    private static final long USER_ID = 101L;
    private static final long TENANT_ID = 202L;
    private static final long CONVERSATION_ID = 901L;
    private static final long AGENT_ID = 500L;
    private static final long USER_MESSAGE_ID = 1001L;
    private static final long ASSISTANT_MESSAGE_ID = 1002L;
    private static final long ASSISTANT_SEQUENCE_NO = 3L;

    private static final String SYSTEM_PROMPT =
            "You are a support agent.";
    private static final String MODEL_NAME = "gpt-5";

    private static final Instant PREPARED_AT =
            Instant.parse("2026-08-09T10:15:30.123Z");

    private static final Instant RAW_COMPLETED_AT =
            Instant.parse("2026-08-09T10:15:31.123456Z");

    private static final Instant COMPLETED_AT =
            Instant.parse("2026-08-09T10:15:31.123Z");

    private static final ChatModelFinishReason FINISH_REASON =
            ChatModelFinishReason.STOP;

    private static final ChatTokenUsage USAGE =
            new ChatTokenUsage(12, 34);

    private static final ActiveAgentRuntime AGENT =
            new ActiveAgentRuntime(
                    AGENT_ID,
                    TENANT_ID,
                    "support-agent",
                    SYSTEM_PROMPT,
                    AgentModelProvider.OPENAI,
                    MODEL_NAME,
                    null
            );

    private static final PreparedConversationTurn PREPARED =
            new PreparedConversationTurn(
                    TENANT_ID,
                    USER_ID,
                    CONVERSATION_ID,
                    AGENT,
                    USER_MESSAGE_ID,
                    2L,
                    ASSISTANT_MESSAGE_ID,
                    ASSISTANT_SEQUENCE_NO,
                    8,
                    PREPARED_AT,
                    new ChatModelRequest(
                            MODEL_NAME,
                            SYSTEM_PROMPT,
                            ChatModelOptions.defaults(),
                            List.of(ChatModelMessage.user("Hello")),
                            List.of()
                    )
            );

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private ConversationTurnMetadataJsonCodec metadataCodec;

    @Mock
    private AuditLogWriter auditLogWriter;

    @Mock
    private Clock clock;

    private DefaultCompleteConversationTurnService service;

    @BeforeEach
    void setUp() {
        service = new DefaultCompleteConversationTurnService(
                messageMapper,
                metadataCodec,
                auditLogWriter,
                clock
        );
    }

    @Test
    void shouldCompleteAssistantMessageWithExactArguments() {
        stubHappyPath();

        CompletedConversationTurn result =
                service.complete(
                        PREPARED,
                        "  Hello world  ",
                        FINISH_REASON,
                        USAGE
                );

        ArgumentCaptor<String> metadataJsonCaptor =
                ArgumentCaptor.forClass(String.class);

        verify(messageMapper)
                .completeAssistantMessage(
                        eq(ASSISTANT_MESSAGE_ID),
                        eq(TENANT_ID),
                        eq(CONVERSATION_ID),
                        eq(ASSISTANT_SEQUENCE_NO),
                        eq("  Hello world  "),
                        eq(MODEL_NAME),
                        eq(12),
                        eq(34),
                        metadataJsonCaptor.capture()
                );

        assertEquals(
                "{}",
                metadataJsonCaptor.getValue()
        );

        ArgumentCaptor<Map<String, Object>> metadataCaptor =
                ArgumentCaptor.forClass(Map.class);

        verify(metadataCodec).encode(metadataCaptor.capture());

        assertEquals(
                Map.of(
                        "provider",
                        "OPENAI",
                        "finishReason",
                        "STOP",
                        "completedAt",
                        COMPLETED_AT.toString()
                ),
                metadataCaptor.getValue()
        );

        ArgumentCaptor<AuditLogCommand> auditCaptor =
                ArgumentCaptor.forClass(AuditLogCommand.class);

        verify(auditLogWriter).write(auditCaptor.capture());

        AuditLogCommand command = auditCaptor.getValue();

        assertEquals(
                AuditActorType.AGENT,
                command.actorType()
        );
        assertEquals(AGENT_ID, command.actorId());
        assertEquals(
                "CONVERSATION_TURN_COMPLETED",
                command.action()
        );
        assertEquals("MESSAGE", command.resourceType());
        assertEquals(
                ASSISTANT_MESSAGE_ID,
                command.resourceId()
        );
        assertEquals(AuditResult.SUCCESS, command.result());

        assertEquals(
                Map.of("status", "CREATING"),
                command.beforeData()
        );

        Map<?, ?> afterData =
                (Map<?, ?>) command.afterData();

        assertEquals("COMPLETED", afterData.get("status"));
        assertEquals(12, afterData.get("promptTokens"));
        assertEquals(34, afterData.get("completionTokens"));

        assertEquals(TENANT_ID, result.tenantId());
        assertEquals(USER_ID, result.userId());
        assertEquals(CONVERSATION_ID, result.conversationId());
        assertEquals(AGENT_ID, result.agentId());
        assertEquals(
                ASSISTANT_MESSAGE_ID,
                result.assistantMessageId()
        );
        assertEquals(
                ASSISTANT_SEQUENCE_NO,
                result.assistantSequenceNo()
        );
        assertEquals(
                "  Hello world  ",
                result.content()
        );
        assertEquals(MODEL_NAME, result.modelName());
        assertEquals(FINISH_REASON, result.finishReason());
        assertEquals(USAGE, result.usage());
        assertEquals(PREPARED_AT, result.createdAt());
        assertEquals(COMPLETED_AT, result.completedAt());
    }

    @Test
    void shouldKeepAuditFreeOfContentAndSystemPrompt() {
        stubHappyPath();

        service.complete(
                PREPARED,
                "Hello world",
                FINISH_REASON,
                USAGE
        );

        ArgumentCaptor<AuditLogCommand> auditCaptor =
                ArgumentCaptor.forClass(AuditLogCommand.class);

        verify(auditLogWriter).write(auditCaptor.capture());

        Map<?, ?> afterData =
                (Map<?, ?>) auditCaptor.getValue().afterData();

        assertFalse(afterData.containsKey("content"));
        assertFalse(afterData.containsKey("systemPrompt"));
        assertFalse(afterData.containsKey("modelConfig"));

        assertEquals(
                "901",
                afterData.get("conversationId")
        );
        assertEquals(
                "1002",
                afterData.get("messageId")
        );
        assertEquals(3L, afterData.get("sequenceNo"));
        assertEquals("OPENAI", afterData.get("modelProvider"));
        assertEquals("gpt-5", afterData.get("modelName"));
        assertEquals("STOP", afterData.get("finishReason"));
        assertEquals(
                COMPLETED_AT.toString(),
                afterData.get("completedAt")
        );
    }

    @ParameterizedTest
    @MethodSource("invalidContent")
    void shouldRejectInvalidAssistantContent(String content) {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.complete(
                        PREPARED,
                        content,
                        FINISH_REASON,
                        USAGE
                )
        );

        verifyNoInteractions(
                messageMapper,
                metadataCodec,
                auditLogWriter,
                clock
        );
    }

    @Test
    void shouldRejectNullFinishReason() {
        assertThrows(
                NullPointerException.class,
                () -> service.complete(
                        PREPARED,
                        "Hello world",
                        null,
                        USAGE
                )
        );

        verifyNoInteractions(
                messageMapper,
                metadataCodec,
                auditLogWriter,
                clock
        );
    }

    @Test
    void shouldRejectNullUsage() {
        assertThrows(
                NullPointerException.class,
                () -> service.complete(
                        PREPARED,
                        "Hello world",
                        FINISH_REASON,
                        null
                )
        );

        verifyNoInteractions(
                messageMapper,
                metadataCodec,
                auditLogWriter,
                clock
        );
    }

    @Test
    void shouldRejectCompletionBeforePreparationTime() {
        when(clock.instant())
                .thenReturn(PREPARED_AT.minusSeconds(1));

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.complete(
                                PREPARED,
                                "Hello world",
                                FINISH_REASON,
                                USAGE
                        )
                );

        assertEquals(
                "Completion time must not be "
                        + "before preparation time",
                exception.getMessage()
        );

        verifyNoInteractions(
                messageMapper,
                metadataCodec,
                auditLogWriter
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void shouldStopWithoutAuditingWhenUpdateCountIsUnexpected(
            int affectedRows
    ) {
        stubClock();
        stubCodec();

        when(messageMapper.completeAssistantMessage(
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                any(),
                any(),
                anyInt(),
                anyInt(),
                any()
        )).thenReturn(affectedRows);

        assertThrows(
                IllegalStateException.class,
                () -> service.complete(
                        PREPARED,
                        "Hello world",
                        FINISH_REASON,
                        USAGE
                )
        );

        verify(metadataCodec).encode(any());

        verify(messageMapper)
                .completeAssistantMessage(
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        any(),
                        any(),
                        anyInt(),
                        anyInt(),
                        any()
                );

        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldNotUpdateWhenMetadataEncodingFails() {
        stubClock();

        IllegalStateException failure =
                new IllegalStateException(
                        "boom"
                );

        when(metadataCodec.encode(any()))
                .thenThrow(failure);

        IllegalStateException actual =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.complete(
                                PREPARED,
                                "Hello world",
                                FINISH_REASON,
                                USAGE
                        )
                );

        assertSame(failure, actual);

        verify(
                messageMapper,
                never()
        ).completeAssistantMessage(
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                any(),
                any(),
                anyInt(),
                anyInt(),
                any()
        );

        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldPropagateAuditFailure() {
        stubHappyPath();

        IllegalStateException failure =
                new IllegalStateException(
                        "Simulated audit failure"
                );

        doThrow(failure)
                .when(auditLogWriter)
                .write(any());

        IllegalStateException actual =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.complete(
                                PREPARED,
                                "Hello world",
                                FINISH_REASON,
                                USAGE
                        )
                );

        assertSame(failure, actual);

        verify(messageMapper)
                .completeAssistantMessage(
                        ASSISTANT_MESSAGE_ID,
                        TENANT_ID,
                        CONVERSATION_ID,
                        ASSISTANT_SEQUENCE_NO,
                        "Hello world",
                        MODEL_NAME,
                        12,
                        34,
                        "{}"
                );
    }

    @Test
    void shouldPerformOperationsInStrictOrder() {
        stubHappyPath();

        service.complete(
                PREPARED,
                "Hello world",
                FINISH_REASON,
                USAGE
        );

        InOrder order = inOrder(
                metadataCodec,
                messageMapper,
                auditLogWriter
        );

        order.verify(metadataCodec).encode(any());

        order.verify(messageMapper)
                .completeAssistantMessage(
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        any(),
                        any(),
                        anyInt(),
                        anyInt(),
                        any()
                );

        order.verify(auditLogWriter).write(any());
    }

    private void stubClock() {
        when(clock.instant()).thenReturn(RAW_COMPLETED_AT);
    }

    private void stubCodec() {
        when(metadataCodec.encode(any()))
                .thenReturn("{}");
    }

    private void stubUpdate() {
        when(messageMapper.completeAssistantMessage(
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                any(),
                any(),
                anyInt(),
                anyInt(),
                any()
        )).thenReturn(1);
    }

    private void stubHappyPath() {
        stubClock();
        stubCodec();
        stubUpdate();
    }

    private static Stream<Arguments> invalidContent() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of(""),
                Arguments.of("   "),
                Arguments.of("x".repeat(50_001))
        );
    }
}
