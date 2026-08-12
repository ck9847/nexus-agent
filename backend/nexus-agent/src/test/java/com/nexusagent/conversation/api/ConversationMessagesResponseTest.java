package com.nexusagent.conversation.api;

import com.nexusagent.conversation.domain.MessageContentType;
import com.nexusagent.conversation.domain.MessageRole;
import com.nexusagent.conversation.domain.MessageStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMessagesResponseTest {

    private static final Instant NOW =
            Instant.parse("2026-08-10T01:00:00Z");

    @Test
    void shouldRejectNullItems() {
        assertThrows(
                NullPointerException.class,
                () -> new ConversationMessagesResponse(
                        null,
                        null,
                        false
                )
        );
    }

    @Test
    void shouldAllowEmptyPageWithoutMore() {
        ConversationMessagesResponse response =
                new ConversationMessagesResponse(
                        List.of(),
                        null,
                        false
                );

        assertTrue(response.items().isEmpty());
        assertNull(response.nextCursor());
        assertEquals(false, response.hasMore());
    }

    @Test
    void shouldRejectMissingCursorWhenHasMore() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationMessagesResponse(
                        List.of(),
                        null,
                        true
                )
        );
    }

    @Test
    void shouldRejectBlankCursorWhenHasMore() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationMessagesResponse(
                        List.of(),
                        "   ",
                        true
                )
        );
    }

    @Test
    void shouldRejectCursorWhenHasNoMore() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationMessagesResponse(
                        List.of(),
                        "next-cursor",
                        false
                )
        );
    }

    @Test
    void shouldCopyItemsImmutably() {
        List<ConversationMessageResponse> original =
                new ArrayList<>();

        original.add(message("902", 1L));

        ConversationMessagesResponse response =
                new ConversationMessagesResponse(
                        original,
                        null,
                        false
                );

        assertNotSame(original, response.items());

        original.add(message("903", 2L));

        assertEquals(1, response.items().size());

        assertThrows(
                UnsupportedOperationException.class,
                () -> response.items().add(
                        message("904", 3L)
                )
        );
    }

    @Test
    void shouldAcceptCursorWhenHasMore() {
        ConversationMessagesResponse response =
                new ConversationMessagesResponse(
                        List.of(message("902", 2L)),
                        "next-cursor",
                        true
                );

        assertEquals(
                "next-cursor",
                response.nextCursor()
        );
        assertEquals(true, response.hasMore());
    }

    private static ConversationMessageResponse message(
            String messageId,
            long sequenceNo
    ) {
        return new ConversationMessageResponse(
                messageId,
                sequenceNo,
                MessageRole.USER,
                "content",
                MessageContentType.TEXT,
                MessageStatus.COMPLETED,
                NOW
        );
    }
}
