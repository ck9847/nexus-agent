package com.nexusagent.conversation.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.model.api.ChatModelToolCall;
import com.nexusagent.model.api.ChatTokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompletedConversationToolCallTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-13T10:15:30.123Z");

    private static final Instant COMPLETED_AT =
            Instant.parse("2026-08-13T10:15:32.123Z");

    private static final ChatTokenUsage USAGE =
            new ChatTokenUsage(5, 7);

    private static ChatModelToolCall toolCall() {
        try {
            return new ChatModelToolCall(
                    "call_123",
                    "create_ticket",
                    new ObjectMapper().readTree(
                            "{\"title\":\"Payment failed\"}"
                    )
            );
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void shouldAcceptValidCompletion() {
        ChatModelToolCall call = toolCall();

        CompletedConversationToolCall completed =
                new CompletedConversationToolCall(
                        202L,
                        101L,
                        901L,
                        500L,
                        1001L,
                        2L,
                        call,
                        7001L,
                        "gpt-5-mini",
                        USAGE,
                        CREATED_AT,
                        COMPLETED_AT
                );

        assertEquals(202L, completed.tenantId());
        assertEquals(101L, completed.userId());
        assertEquals(901L, completed.conversationId());
        assertEquals(500L, completed.agentId());
        assertEquals(1001L, completed.assistantMessageId());
        assertEquals(2L, completed.assistantSequenceNo());
        assertSame(call, completed.toolCall());
        assertEquals(7001L, completed.toolExecutionId());
        assertEquals("gpt-5-mini", completed.modelName());
        assertSame(USAGE, completed.usage());
        assertEquals(CREATED_AT, completed.createdAt());
        assertEquals(COMPLETED_AT, completed.completedAt());
    }

    @ParameterizedTest
    @MethodSource("nonPositiveIds")
    void shouldRejectNonPositiveIds(
            long tenantId,
            long userId,
            long conversationId,
            long agentId,
            long assistantMessageId,
            long assistantSequenceNo,
            long toolExecutionId
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompletedConversationToolCall(
                        tenantId,
                        userId,
                        conversationId,
                        agentId,
                        assistantMessageId,
                        assistantSequenceNo,
                        toolCall(),
                        toolExecutionId,
                        "gpt-5-mini",
                        USAGE,
                        CREATED_AT,
                        COMPLETED_AT
                )
        );
    }

    @Test
    void shouldRejectIdenticalMessageAndExecutionIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompletedConversationToolCall(
                        202L,
                        101L,
                        901L,
                        500L,
                        1001L,
                        2L,
                        toolCall(),
                        1001L,
                        "gpt-5-mini",
                        USAGE,
                        CREATED_AT,
                        COMPLETED_AT
                )
        );
    }

    @Test
    void shouldRejectNullToolCall() {
        assertThrows(
                NullPointerException.class,
                () -> new CompletedConversationToolCall(
                        202L,
                        101L,
                        901L,
                        500L,
                        1001L,
                        2L,
                        null,
                        7001L,
                        "gpt-5-mini",
                        USAGE,
                        CREATED_AT,
                        COMPLETED_AT
                )
        );
    }

    @Test
    void shouldRejectNullUsage() {
        assertThrows(
                NullPointerException.class,
                () -> new CompletedConversationToolCall(
                        202L,
                        101L,
                        901L,
                        500L,
                        1001L,
                        2L,
                        toolCall(),
                        7001L,
                        "gpt-5-mini",
                        null,
                        CREATED_AT,
                        COMPLETED_AT
                )
        );
    }

    @Test
    void shouldRejectCompletionBeforeCreation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompletedConversationToolCall(
                        202L,
                        101L,
                        901L,
                        500L,
                        1001L,
                        2L,
                        toolCall(),
                        7001L,
                        "gpt-5-mini",
                        USAGE,
                        COMPLETED_AT,
                        CREATED_AT
                )
        );
    }

    private static Stream<Arguments> nonPositiveIds() {
        return Stream.of(
                Arguments.of(
                        0L, 101L, 901L, 500L, 1001L, 2L, 7001L
                ),
                Arguments.of(
                        202L, 0L, 901L, 500L, 1001L, 2L, 7001L
                ),
                Arguments.of(
                        202L, 101L, 0L, 500L, 1001L, 2L, 7001L
                ),
                Arguments.of(
                        202L, 101L, 901L, 0L, 1001L, 2L, 7001L
                ),
                Arguments.of(
                        202L, 101L, 901L, 500L, 0L, 2L, 7001L
                ),
                Arguments.of(
                        202L, 101L, 901L, 500L, 1001L, 0L, 7001L
                ),
                Arguments.of(
                        202L, 101L, 901L, 500L, 1001L, 2L, 0L
                )
        );
    }
}
