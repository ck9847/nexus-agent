package com.nexusagent.conversation.internal;

import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatTokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompletedConversationTurnTest {

    private static final long TENANT_ID = 202L;
    private static final long USER_ID = 101L;
    private static final long CONVERSATION_ID = 901L;
    private static final long AGENT_ID = 500L;
    private static final long ASSISTANT_MESSAGE_ID = 1002L;
    private static final long ASSISTANT_SEQUENCE_NO = 3L;

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-09T10:15:30.123Z");

    private static final Instant COMPLETED_AT =
            Instant.parse("2026-08-09T10:15:31.123Z");

    private static CompletedConversationTurn turn(
            long tenantId,
            long userId,
            long conversationId,
            long agentId,
            long assistantMessageId,
            long assistantSequenceNo,
            String content,
            String modelName,
            ChatModelFinishReason finishReason,
            ChatTokenUsage usage,
            Instant createdAt,
            Instant completedAt
    ) {
        return new CompletedConversationTurn(
                tenantId,
                userId,
                conversationId,
                agentId,
                assistantMessageId,
                assistantSequenceNo,
                content,
                modelName,
                finishReason,
                usage,
                createdAt,
                completedAt
        );
    }

    @Test
    void shouldAcceptValidTurnAndPreserveContentWhitespace() {
        CompletedConversationTurn turn =
                turn(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT_ID,
                        ASSISTANT_MESSAGE_ID,
                        ASSISTANT_SEQUENCE_NO,
                        "  Hello world  ",
                        "gpt-5",
                        ChatModelFinishReason.STOP,
                        new ChatTokenUsage(12, 34),
                        CREATED_AT,
                        COMPLETED_AT
                );

        assertEquals(TENANT_ID, turn.tenantId());
        assertEquals(USER_ID, turn.userId());
        assertEquals(CONVERSATION_ID, turn.conversationId());
        assertEquals(AGENT_ID, turn.agentId());
        assertEquals(
                ASSISTANT_MESSAGE_ID,
                turn.assistantMessageId()
        );
        assertEquals(
                ASSISTANT_SEQUENCE_NO,
                turn.assistantSequenceNo()
        );
        assertEquals(
                "  Hello world  ",
                turn.content()
        );
        assertEquals("gpt-5", turn.modelName());
        assertEquals(
                ChatModelFinishReason.STOP,
                turn.finishReason()
        );
        assertEquals(
                new ChatTokenUsage(12, 34),
                turn.usage()
        );
        assertEquals(CREATED_AT, turn.createdAt());
        assertEquals(COMPLETED_AT, turn.completedAt());
    }

    @ParameterizedTest
    @MethodSource("nonPositiveIds")
    void shouldRejectNonPositiveIds(
            long tenantId,
            long userId,
            long conversationId,
            long agentId,
            long assistantMessageId,
            long assistantSequenceNo
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> turn(
                        tenantId,
                        userId,
                        conversationId,
                        agentId,
                        assistantMessageId,
                        assistantSequenceNo,
                        "Hello world",
                        "gpt-5",
                        ChatModelFinishReason.STOP,
                        new ChatTokenUsage(12, 34),
                        CREATED_AT,
                        COMPLETED_AT
                )
        );
    }

    @ParameterizedTest
    @MethodSource("invalidContent")
    void shouldRejectInvalidContent(String content) {
        assertThrows(
                IllegalArgumentException.class,
                () -> turn(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT_ID,
                        ASSISTANT_MESSAGE_ID,
                        ASSISTANT_SEQUENCE_NO,
                        content,
                        "gpt-5",
                        ChatModelFinishReason.STOP,
                        new ChatTokenUsage(12, 34),
                        CREATED_AT,
                        COMPLETED_AT
                )
        );
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void shouldRejectBlankModelName(String modelName) {
        assertThrows(
                IllegalArgumentException.class,
                () -> turn(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT_ID,
                        ASSISTANT_MESSAGE_ID,
                        ASSISTANT_SEQUENCE_NO,
                        "Hello world",
                        modelName,
                        ChatModelFinishReason.STOP,
                        new ChatTokenUsage(12, 34),
                        CREATED_AT,
                        COMPLETED_AT
                )
        );
    }

    @Test
    void shouldRejectNullFinishReason() {
        NullPointerException exception =
                assertThrows(
                        NullPointerException.class,
                        () -> turn(
                                TENANT_ID,
                                USER_ID,
                                CONVERSATION_ID,
                                AGENT_ID,
                                ASSISTANT_MESSAGE_ID,
                                ASSISTANT_SEQUENCE_NO,
                                "Hello world",
                                "gpt-5",
                                null,
                                new ChatTokenUsage(12, 34),
                                CREATED_AT,
                                COMPLETED_AT
                        )
                );

        assertEquals(
                "finishReason must not be null",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectNullUsage() {
        NullPointerException exception =
                assertThrows(
                        NullPointerException.class,
                        () -> turn(
                                TENANT_ID,
                                USER_ID,
                                CONVERSATION_ID,
                                AGENT_ID,
                                ASSISTANT_MESSAGE_ID,
                                ASSISTANT_SEQUENCE_NO,
                                "Hello world",
                                "gpt-5",
                                ChatModelFinishReason.STOP,
                                null,
                                CREATED_AT,
                                COMPLETED_AT
                        )
                );

        assertEquals(
                "usage must not be null",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectNullCreatedAt() {
        assertThrows(
                NullPointerException.class,
                () -> turn(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT_ID,
                        ASSISTANT_MESSAGE_ID,
                        ASSISTANT_SEQUENCE_NO,
                        "Hello world",
                        "gpt-5",
                        ChatModelFinishReason.STOP,
                        new ChatTokenUsage(12, 34),
                        null,
                        COMPLETED_AT
                )
        );
    }

    @Test
    void shouldRejectNullCompletedAt() {
        assertThrows(
                NullPointerException.class,
                () -> turn(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT_ID,
                        ASSISTANT_MESSAGE_ID,
                        ASSISTANT_SEQUENCE_NO,
                        "Hello world",
                        "gpt-5",
                        ChatModelFinishReason.STOP,
                        new ChatTokenUsage(12, 34),
                        CREATED_AT,
                        null
                )
        );
    }

    @Test
    void shouldRejectCompletedAtBeforeCreatedAt() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> turn(
                                TENANT_ID,
                                USER_ID,
                                CONVERSATION_ID,
                                AGENT_ID,
                                ASSISTANT_MESSAGE_ID,
                                ASSISTANT_SEQUENCE_NO,
                                "Hello world",
                                "gpt-5",
                                ChatModelFinishReason.STOP,
                                new ChatTokenUsage(12, 34),
                                COMPLETED_AT,
                                CREATED_AT
                        )
                );

        assertEquals(
                "completedAt must not be before createdAt",
                exception.getMessage()
        );
    }

    private static Stream<Arguments> nonPositiveIds() {
        return Stream.of(
                Arguments.of(
                        0L,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT_ID,
                        ASSISTANT_MESSAGE_ID,
                        ASSISTANT_SEQUENCE_NO
                ),
                Arguments.of(
                        TENANT_ID,
                        0L,
                        CONVERSATION_ID,
                        AGENT_ID,
                        ASSISTANT_MESSAGE_ID,
                        ASSISTANT_SEQUENCE_NO
                ),
                Arguments.of(
                        TENANT_ID,
                        USER_ID,
                        0L,
                        AGENT_ID,
                        ASSISTANT_MESSAGE_ID,
                        ASSISTANT_SEQUENCE_NO
                ),
                Arguments.of(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        0L,
                        ASSISTANT_MESSAGE_ID,
                        ASSISTANT_SEQUENCE_NO
                ),
                Arguments.of(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT_ID,
                        0L,
                        ASSISTANT_SEQUENCE_NO
                ),
                Arguments.of(
                        TENANT_ID,
                        USER_ID,
                        CONVERSATION_ID,
                        AGENT_ID,
                        ASSISTANT_MESSAGE_ID,
                        0L
                )
        );
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
