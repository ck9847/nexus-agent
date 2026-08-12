package com.nexusagent.conversation.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationMessagesQueryTest {

    private static final int MAX_CURSOR_LENGTH = 256;

    @ParameterizedTest
    @ValueSource(ints = {1, 20, 100})
    void shouldAcceptValidLimits(int limit) {
        ConversationMessagesQuery query =
                new ConversationMessagesQuery(limit, null);

        assertEquals(limit, query.limit());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void shouldRejectOutOfRangeLimits(int limit) {
        assertThrows(
                InvalidConversationQueryException.class,
                () -> new ConversationMessagesQuery(limit, null)
        );
    }

    @Test
    void shouldAcceptNullCursor() {
        ConversationMessagesQuery query =
                new ConversationMessagesQuery(20, null);

        assertNull(query.cursor());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void shouldNormalizeBlankCursorToNull(String cursor) {
        ConversationMessagesQuery query =
                new ConversationMessagesQuery(20, cursor);

        assertNull(query.cursor());
    }

    @Test
    void shouldTrimCursorWhitespace() {
        ConversationMessagesQuery query =
                new ConversationMessagesQuery(
                        20,
                        "  cursor-value  "
                );

        assertEquals(
                "cursor-value",
                query.cursor()
        );
    }

    @Test
    void shouldTrimBeforeCheckingCursorLength() {
        String cursor =
                "  "
                        + "x".repeat(MAX_CURSOR_LENGTH)
                        + "  ";

        ConversationMessagesQuery query =
                new ConversationMessagesQuery(20, cursor);

        assertEquals(
                MAX_CURSOR_LENGTH,
                query.cursor().length()
        );
    }

    @Test
    void shouldAcceptCursorAtMaximumLength() {
        ConversationMessagesQuery query =
                new ConversationMessagesQuery(
                        20,
                        "x".repeat(MAX_CURSOR_LENGTH)
                );

        assertEquals(
                MAX_CURSOR_LENGTH,
                query.cursor().length()
        );
    }

    @Test
    void shouldRejectCursorOverMaximumLength() {
        assertThrows(
                InvalidConversationQueryException.class,
                () -> new ConversationMessagesQuery(
                        20,
                        "x".repeat(MAX_CURSOR_LENGTH + 1)
                )
        );
    }
}
