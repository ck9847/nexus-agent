package com.nexusagent.conversation.api;

import com.nexusagent.model.api.ChatModelFinishReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationTurnStreamEventTest {

    private static final long USER_SEQUENCE_NO = 2L;
    private static final long ASSISTANT_SEQUENCE_NO = 3L;
    private static final int CONVERSATION_VERSION = 8;
    private static final Instant CREATED_AT =
            Instant.parse("2026-08-09T10:15:30.123Z");
    private static final Instant COMPLETED_AT =
            Instant.parse("2026-08-09T10:15:31.123Z");

    @Test
    void shouldConstructStartedWithExactFields() {
        ConversationTurnStreamEvent.Started event =
                new ConversationTurnStreamEvent.Started(
                        "901",
                        "500",
                        "1001",
                        USER_SEQUENCE_NO,
                        "1002",
                        ASSISTANT_SEQUENCE_NO,
                        CONVERSATION_VERSION,
                        CREATED_AT
                );

        assertEquals("901", event.conversationId());
        assertEquals("500", event.agentId());
        assertEquals("1001", event.userMessageId());
        assertEquals(USER_SEQUENCE_NO, event.userSequenceNo());
        assertEquals("1002", event.assistantMessageId());
        assertEquals(ASSISTANT_SEQUENCE_NO, event.assistantSequenceNo());
        assertEquals(CONVERSATION_VERSION, event.conversationVersion());
        assertEquals(CREATED_AT, event.createdAt());
    }

    @Test
    void shouldRejectBlankStartedFields() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamEvent.Started(
                        "  ",
                        "500",
                        "1001",
                        USER_SEQUENCE_NO,
                        "1002",
                        ASSISTANT_SEQUENCE_NO,
                        CONVERSATION_VERSION,
                        CREATED_AT
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamEvent.Started(
                        "901",
                        "  ",
                        "1001",
                        USER_SEQUENCE_NO,
                        "1002",
                        ASSISTANT_SEQUENCE_NO,
                        CONVERSATION_VERSION,
                        CREATED_AT
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamEvent.Started(
                        "901",
                        "500",
                        "  ",
                        USER_SEQUENCE_NO,
                        "1002",
                        ASSISTANT_SEQUENCE_NO,
                        CONVERSATION_VERSION,
                        CREATED_AT
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamEvent.Started(
                        "901",
                        "500",
                        "1001",
                        USER_SEQUENCE_NO,
                        "  ",
                        ASSISTANT_SEQUENCE_NO,
                        CONVERSATION_VERSION,
                        CREATED_AT
                )
        );
    }

    @Test
    void shouldRejectNonPositiveOrNonConsecutiveStartedSequences() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamEvent.Started(
                        "901",
                        "500",
                        "1001",
                        0L,
                        "1002",
                        ASSISTANT_SEQUENCE_NO,
                        CONVERSATION_VERSION,
                        CREATED_AT
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamEvent.Started(
                        "901",
                        "500",
                        "1001",
                        USER_SEQUENCE_NO,
                        "1002",
                        USER_SEQUENCE_NO + 5L,
                        CONVERSATION_VERSION,
                        CREATED_AT
                )
        );
    }

    @Test
    void shouldRejectNonPositiveStartedVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamEvent.Started(
                        "901",
                        "500",
                        "1001",
                        USER_SEQUENCE_NO,
                        "1002",
                        ASSISTANT_SEQUENCE_NO,
                        0,
                        CREATED_AT
                )
        );
    }

    @Test
    void shouldRejectNullStartedCreatedAt() {
        assertThrows(
                NullPointerException.class,
                () -> new ConversationTurnStreamEvent.Started(
                        "901",
                        "500",
                        "1001",
                        USER_SEQUENCE_NO,
                        "1002",
                        ASSISTANT_SEQUENCE_NO,
                        CONVERSATION_VERSION,
                        null
                )
        );
    }

    @Test
    void shouldConstructTextDeltaWithExactText() {
        ConversationTurnStreamEvent.TextDelta event =
                new ConversationTurnStreamEvent.TextDelta("Hello");

        assertEquals("Hello", event.text());
    }

    @Test
    void shouldAcceptWhitespaceOnlyTextDelta() {
        ConversationTurnStreamEvent.TextDelta event =
                new ConversationTurnStreamEvent.TextDelta("   ");

        assertEquals("   ", event.text());
    }

    @Test
    void shouldRejectEmptyTextDelta() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamEvent.TextDelta("")
        );
    }

    @Test
    void shouldRejectNullTextDelta() {
        assertThrows(
                NullPointerException.class,
                () -> new ConversationTurnStreamEvent.TextDelta(null)
        );
    }

    @Test
    void shouldConstructCompletedWithExactFields() {
        ConversationTurnStreamEvent.Completed event =
                new ConversationTurnStreamEvent.Completed(
                        "901",
                        "500",
                        "1002",
                        ASSISTANT_SEQUENCE_NO,
                        CONVERSATION_VERSION,
                        "gpt-5",
                        ChatModelFinishReason.STOP,
                        12,
                        34,
                        COMPLETED_AT
                );

        assertEquals("901", event.conversationId());
        assertEquals("500", event.agentId());
        assertEquals("1002", event.assistantMessageId());
        assertEquals(
                ASSISTANT_SEQUENCE_NO,
                event.assistantSequenceNo()
        );
        assertEquals(CONVERSATION_VERSION, event.conversationVersion());
        assertEquals("gpt-5", event.modelName());
        assertEquals(
                ChatModelFinishReason.STOP,
                event.finishReason()
        );
        assertEquals(12, event.promptTokens());
        assertEquals(34, event.completionTokens());
        assertEquals(COMPLETED_AT, event.completedAt());
    }

    @Test
    void shouldRejectBlankCompletedModelName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamEvent.Completed(
                        "901",
                        "500",
                        "1002",
                        ASSISTANT_SEQUENCE_NO,
                        CONVERSATION_VERSION,
                        "  ",
                        ChatModelFinishReason.STOP,
                        12,
                        34,
                        COMPLETED_AT
                )
        );
    }

    @Test
    void shouldRejectInvalidCompletedSequenceOrVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamEvent.Completed(
                        "901",
                        "500",
                        "1002",
                        0L,
                        CONVERSATION_VERSION,
                        "gpt-5",
                        ChatModelFinishReason.STOP,
                        12,
                        34,
                        COMPLETED_AT
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamEvent.Completed(
                        "901",
                        "500",
                        "1002",
                        ASSISTANT_SEQUENCE_NO,
                        0,
                        "gpt-5",
                        ChatModelFinishReason.STOP,
                        12,
                        34,
                        COMPLETED_AT
                )
        );
    }

    @Test
    void shouldRejectNegativeCompletedTokenCounts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamEvent.Completed(
                        "901",
                        "500",
                        "1002",
                        ASSISTANT_SEQUENCE_NO,
                        CONVERSATION_VERSION,
                        "gpt-5",
                        ChatModelFinishReason.STOP,
                        -1,
                        34,
                        COMPLETED_AT
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamEvent.Completed(
                        "901",
                        "500",
                        "1002",
                        ASSISTANT_SEQUENCE_NO,
                        CONVERSATION_VERSION,
                        "gpt-5",
                        ChatModelFinishReason.STOP,
                        12,
                        -1,
                        COMPLETED_AT
                )
        );
    }

    @Test
    void shouldRejectNullCompletedFinishReasonOrCompletedAt() {
        assertThrows(
                NullPointerException.class,
                () -> new ConversationTurnStreamEvent.Completed(
                        "901",
                        "500",
                        "1002",
                        ASSISTANT_SEQUENCE_NO,
                        CONVERSATION_VERSION,
                        "gpt-5",
                        null,
                        12,
                        34,
                        COMPLETED_AT
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new ConversationTurnStreamEvent.Completed(
                        "901",
                        "500",
                        "1002",
                        ASSISTANT_SEQUENCE_NO,
                        CONVERSATION_VERSION,
                        "gpt-5",
                        ChatModelFinishReason.STOP,
                        12,
                        34,
                        null
                )
        );
    }
}
