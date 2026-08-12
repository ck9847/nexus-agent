package com.nexusagent.conversation.internal;

import com.nexusagent.agent.api.ActiveAgentRuntime;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.conversation.internal.persistence.MessageMapper;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelMessage;
import com.nexusagent.model.api.ChatModelOptions;
import com.nexusagent.model.api.ChatModelRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultFailConversationTurnServiceTest {

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

    private static final Instant RAW_FAILED_AT =
            Instant.parse("2026-08-09T10:15:31.123456Z");

    private static final Instant FAILED_AT =
            Instant.parse("2026-08-09T10:15:31.123Z");

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

    private static final ChatModelException RATE_LIMIT_FAILURE =
            new ChatModelException(
                    ChatModelErrorCategory.RATE_LIMIT,
                    "boom",
                    429,
                    null
            );

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private ConversationTurnMetadataJsonCodec metadataCodec;

    @Mock
    private AuditLogWriter auditLogWriter;

    @Mock
    private Clock clock;

    private DefaultFailConversationTurnService service;

    @BeforeEach
    void setUp() {
        service = new DefaultFailConversationTurnService(
                messageMapper,
                metadataCodec,
                auditLogWriter,
                clock
        );
    }

    @Test
    void shouldFailAssistantMessageWithExactArguments() {
        stubHappyPath();

        service.fail(PREPARED, RATE_LIMIT_FAILURE);

        ArgumentCaptor<String> metadataJsonCaptor =
                ArgumentCaptor.forClass(String.class);

        verify(messageMapper)
                .failAssistantMessage(
                        eq(ASSISTANT_MESSAGE_ID),
                        eq(TENANT_ID),
                        eq(CONVERSATION_ID),
                        eq(ASSISTANT_SEQUENCE_NO),
                        eq(MODEL_NAME),
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
                        "errorCode",
                        "CHAT_MODEL_RATE_LIMIT",
                        "retryable",
                        true,
                        "providerStatus",
                        429,
                        "failedAt",
                        FAILED_AT.toString()
                ),
                metadataCaptor.getValue()
        );

        ArgumentCaptor<AuditLogCommand> auditCaptor =
                ArgumentCaptor.forClass(AuditLogCommand.class);

        verify(auditLogWriter).write(auditCaptor.capture());

        assertAuditCommand(
                auditCaptor.getValue(),
                "CHAT_MODEL_RATE_LIMIT"
        );
    }

    @Test
    void shouldWriteProviderStatusWhenHttpStatusPresent() {
        stubHappyPath();

        service.fail(PREPARED, RATE_LIMIT_FAILURE);

        ArgumentCaptor<Map<String, Object>> metadataCaptor =
                ArgumentCaptor.forClass(Map.class);

        verify(metadataCodec).encode(metadataCaptor.capture());

        assertEquals(
                429,
                metadataCaptor.getValue()
                        .get("providerStatus")
        );

        ArgumentCaptor<AuditLogCommand> auditCaptor =
                ArgumentCaptor.forClass(AuditLogCommand.class);

        verify(auditLogWriter).write(auditCaptor.capture());

        Map<?, ?> afterData =
                (Map<?, ?>) auditCaptor.getValue().afterData();

        assertEquals(429, afterData.get("providerStatus"));
    }

    @Test
    void shouldOmitProviderStatusWhenHttpStatusAbsent() {
        stubHappyPath();

        service.fail(
                PREPARED,
                new ChatModelException(
                        ChatModelErrorCategory.TIMEOUT,
                        "timed out"
                )
        );

        ArgumentCaptor<Map<String, Object>> metadataCaptor =
                ArgumentCaptor.forClass(Map.class);

        verify(metadataCodec).encode(metadataCaptor.capture());

        assertFalse(
                metadataCaptor.getValue()
                        .containsKey("providerStatus")
        );

        ArgumentCaptor<AuditLogCommand> auditCaptor =
                ArgumentCaptor.forClass(AuditLogCommand.class);

        verify(auditLogWriter).write(auditCaptor.capture());

        Map<?, ?> afterData =
                (Map<?, ?>) auditCaptor.getValue().afterData();

        assertFalse(afterData.containsKey("providerStatus"));
    }

    @ParameterizedTest
    @EnumSource(ChatModelErrorCategory.class)
    void shouldMapEveryCategoryToErrorCodeAndRetryable(
            ChatModelErrorCategory category
    ) {
        stubHappyPath();

        service.fail(
                PREPARED,
                new ChatModelException(category, "boom")
        );

        String expectedErrorCode =
                "CHAT_MODEL_" + category.name();

        ArgumentCaptor<Map<String, Object>> metadataCaptor =
                ArgumentCaptor.forClass(Map.class);

        verify(metadataCodec).encode(metadataCaptor.capture());

        Map<?, ?> metadata = metadataCaptor.getValue();

        assertEquals(
                expectedErrorCode,
                metadata.get("errorCode")
        );
        assertEquals(
                category.retryable(),
                metadata.get("retryable")
        );
        assertFalse(metadata.containsKey("providerStatus"));

        ArgumentCaptor<AuditLogCommand> auditCaptor =
                ArgumentCaptor.forClass(AuditLogCommand.class);

        verify(auditLogWriter).write(auditCaptor.capture());

        assertEquals(
                expectedErrorCode,
                auditCaptor.getValue().errorCode()
        );
    }

    @Test
    void shouldNotLeakSensitiveFailureMessage() {
        stubHappyPath();

        service.fail(
                PREPARED,
                new ChatModelException(
                        ChatModelErrorCategory.CONNECTION,
                        "provider connection failed "
                                + "with key sk-secret-xyz-123",
                        503,
                        null
                )
        );

        ArgumentCaptor<Map<String, Object>> metadataCaptor =
                ArgumentCaptor.forClass(Map.class);

        verify(metadataCodec).encode(metadataCaptor.capture());

        String metadataAsText =
                String.valueOf(metadataCaptor.getValue());

        assertFalse(
                metadataAsText.contains("sk-secret-xyz-123")
        );

        ArgumentCaptor<AuditLogCommand> auditCaptor =
                ArgumentCaptor.forClass(AuditLogCommand.class);

        verify(auditLogWriter).write(auditCaptor.capture());

        AuditLogCommand command = auditCaptor.getValue();

        assertEquals(
                "Chat model turn failed",
                command.errorMessage()
        );

        assertFalse(
                String.valueOf(command.afterData())
                        .contains("sk-secret-xyz-123")
        );
        assertFalse(
                String.valueOf(command.beforeData())
                        .contains("sk-secret-xyz-123")
        );
        assertFalse(
                command.errorMessage()
                        .contains("sk-secret-xyz-123")
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void shouldStopWithoutAuditingWhenUpdateCountIsUnexpected(
            int affectedRows
    ) {
        stubClock();
        stubCodec();

        when(messageMapper.failAssistantMessage(
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                any(),
                any()
        )).thenReturn(affectedRows);

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.fail(
                                PREPARED,
                                RATE_LIMIT_FAILURE
                        )
                );

        assertEquals(
                "Expected one assistant message "
                        + "to be failed",
                exception.getMessage()
        );

        verify(metadataCodec).encode(any());

        verify(messageMapper)
                .failAssistantMessage(
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        any(),
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
                        () -> service.fail(
                                PREPARED,
                                RATE_LIMIT_FAILURE
                        )
                );

        assertSame(failure, actual);

        verify(
                messageMapper,
                never()
        ).failAssistantMessage(
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                any(),
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
                        () -> service.fail(
                                PREPARED,
                                RATE_LIMIT_FAILURE
                        )
                );

        assertSame(failure, actual);

        verify(messageMapper)
                .failAssistantMessage(
                        ASSISTANT_MESSAGE_ID,
                        TENANT_ID,
                        CONVERSATION_ID,
                        ASSISTANT_SEQUENCE_NO,
                        MODEL_NAME,
                        "{}"
                );
    }

    @Test
    void shouldRejectFailureBeforePreparationTime() {
        when(clock.instant())
                .thenReturn(PREPARED_AT.minusSeconds(1));

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.fail(
                                PREPARED,
                                RATE_LIMIT_FAILURE
                        )
                );

        assertEquals(
                "Failure time must not be "
                        + "before preparation time",
                exception.getMessage()
        );

        verifyNoInteractions(
                messageMapper,
                metadataCodec,
                auditLogWriter
        );
    }

    @Test
    void shouldRejectNullPreparedTurn() {
        assertThrows(
                NullPointerException.class,
                () -> service.fail(
                        null,
                        RATE_LIMIT_FAILURE
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
    void shouldRejectNullFailure() {
        assertThrows(
                NullPointerException.class,
                () -> service.fail(
                        PREPARED,
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
    void shouldPerformOperationsInStrictOrder() {
        stubHappyPath();

        service.fail(PREPARED, RATE_LIMIT_FAILURE);

        InOrder order = inOrder(
                metadataCodec,
                messageMapper,
                auditLogWriter
        );

        order.verify(metadataCodec).encode(any());

        order.verify(messageMapper)
                .failAssistantMessage(
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        any(),
                        any()
                );

        order.verify(auditLogWriter).write(any());
    }

    private void assertAuditCommand(
            AuditLogCommand command,
            String expectedErrorCode
    ) {
        assertEquals(
                AuditActorType.AGENT,
                command.actorType()
        );
        assertEquals(AGENT_ID, command.actorId());
        assertEquals(
                "CONVERSATION_TURN_FAILED",
                command.action()
        );
        assertEquals("MESSAGE", command.resourceType());
        assertEquals(
                ASSISTANT_MESSAGE_ID,
                command.resourceId()
        );
        assertEquals(AuditResult.FAILURE, command.result());
        assertEquals(
                expectedErrorCode,
                command.errorCode()
        );
        assertEquals(
                "Chat model turn failed",
                command.errorMessage()
        );

        assertEquals(
                Map.of("status", "CREATING"),
                command.beforeData()
        );

        Map<?, ?> afterData =
                (Map<?, ?>) command.afterData();

        assertEquals("FAILED", afterData.get("status"));
        assertEquals("901", afterData.get("conversationId"));
        assertEquals(
                "1002",
                afterData.get("messageId")
        );
        assertEquals(3L, afterData.get("sequenceNo"));
        assertEquals("OPENAI", afterData.get("modelProvider"));
        assertEquals("gpt-5", afterData.get("modelName"));
        assertEquals(
                expectedErrorCode,
                afterData.get("errorCode")
        );
        assertEquals(true, afterData.get("retryable"));
        assertEquals(
                FAILED_AT.toString(),
                afterData.get("failedAt")
        );

        assertFalse(afterData.containsKey("content"));
        assertFalse(afterData.containsKey("systemPrompt"));
        assertFalse(afterData.containsKey("modelConfig"));
    }

    private void stubClock() {
        when(clock.instant()).thenReturn(RAW_FAILED_AT);
    }

    private void stubCodec() {
        when(metadataCodec.encode(any()))
                .thenReturn("{}");
    }

    private void stubUpdate() {
        when(messageMapper.failAssistantMessage(
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                any(),
                any()
        )).thenReturn(1);
    }

    private void stubHappyPath() {
        stubClock();
        stubCodec();
        stubUpdate();
    }
}
