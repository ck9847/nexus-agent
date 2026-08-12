package com.nexusagent.model.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatModelStreamEventTest {

    @Test
    void shouldAcceptTextDelta() {
        ChatModelStreamEvent.TextDelta event =
                new ChatModelStreamEvent.TextDelta("Hello");

        assertEquals("Hello", event.text());
    }

    @Test
    void shouldAcceptWhitespaceOnlyTextDelta() {
        ChatModelStreamEvent.TextDelta event =
                new ChatModelStreamEvent.TextDelta("   ");

        assertEquals("   ", event.text());
    }

    @Test
    void shouldRejectEmptyTextDelta() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChatModelStreamEvent.TextDelta("")
        );
    }

    @Test
    void shouldRejectNullTextDelta() {
        assertThrows(
                NullPointerException.class,
                () -> new ChatModelStreamEvent.TextDelta(null)
        );
    }

    @Test
    void shouldAcceptCallIdOnlyToolCallDelta() {
        ChatModelStreamEvent.ToolCallDelta event =
                new ChatModelStreamEvent.ToolCallDelta(
                        0,
                        "call_",
                        null,
                        null
                );

        assertEquals("call_", event.callIdFragment());
        assertNull(event.nameFragment());
        assertNull(event.argumentsFragment());
    }

    @Test
    void shouldAcceptNameOnlyToolCallDelta() {
        ChatModelStreamEvent.ToolCallDelta event =
                new ChatModelStreamEvent.ToolCallDelta(
                        0,
                        null,
                        "search",
                        null
                );

        assertEquals("search", event.nameFragment());
    }

    @Test
    void shouldAcceptArgumentsOnlyToolCallDelta() {
        ChatModelStreamEvent.ToolCallDelta event =
                new ChatModelStreamEvent.ToolCallDelta(
                        0,
                        null,
                        null,
                        "{\"query\":"
                );

        assertEquals(
                "{\"query\":",
                event.argumentsFragment()
        );
    }

    @Test
    void shouldRejectEmptyToolCallDelta() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChatModelStreamEvent.ToolCallDelta(
                        0,
                        null,
                        null,
                        null
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new ChatModelStreamEvent.ToolCallDelta(
                        0,
                        "",
                        "",
                        ""
                )
        );
    }

    @Test
    void shouldRejectNegativeToolCallIndex() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChatModelStreamEvent.ToolCallDelta(
                        -1,
                        "call_",
                        null,
                        null
                )
        );
    }

    @Test
    void shouldAcceptCompletedWithReasonAndUsage() {
        ChatModelStreamEvent.Completed event =
                new ChatModelStreamEvent.Completed(
                        ChatModelFinishReason.STOP,
                        new ChatTokenUsage(10, 20)
                );

        assertEquals(
                ChatModelFinishReason.STOP,
                event.finishReason()
        );
        assertNotNull(event.usage());
    }

    @Test
    void shouldRejectCompletedWithoutReason() {
        assertThrows(
                NullPointerException.class,
                () -> new ChatModelStreamEvent.Completed(
                        null,
                        new ChatTokenUsage(10, 20)
                )
        );
    }

    @Test
    void shouldRejectCompletedWithoutUsage() {
        assertThrows(
                NullPointerException.class,
                () -> new ChatModelStreamEvent.Completed(
                        ChatModelFinishReason.STOP,
                        null
                )
        );
    }
}
