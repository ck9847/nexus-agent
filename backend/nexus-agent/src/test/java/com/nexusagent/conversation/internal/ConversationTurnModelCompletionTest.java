package com.nexusagent.conversation.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatModelToolCall;
import com.nexusagent.model.api.ChatTokenUsage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationTurnModelCompletionTest {

    private static final ChatTokenUsage USAGE =
            new ChatTokenUsage(5, 7);

    @Test
    void shouldAcceptValidTextCompletion() {
        ConversationTurnModelCompletion.Text completion =
                new ConversationTurnModelCompletion.Text(
                        "Hello world",
                        ChatModelFinishReason.STOP,
                        USAGE
                );

        assertEquals("Hello world", completion.content());
        assertEquals(
                ChatModelFinishReason.STOP,
                completion.finishReason()
        );
        assertSame(USAGE, completion.usage());
    }

    @Test
    void shouldRejectBlankTextCompletion() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnModelCompletion.Text(
                        "   ",
                        ChatModelFinishReason.STOP,
                        USAGE
                )
        );
    }

    @Test
    void shouldRejectTextCompletionWithToolCallsFinish() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnModelCompletion.Text(
                        "Hello",
                        ChatModelFinishReason.TOOL_CALLS,
                        USAGE
                )
        );
    }

    @Test
    void shouldAcceptValidToolCallCompletion() throws Exception {
        ChatModelToolCall call = new ChatModelToolCall(
                "call-1",
                "create_ticket",
                new ObjectMapper().readTree(
                        "{\"title\":\"Server down\"}"
                )
        );

        ConversationTurnModelCompletion.ToolCall completion =
                new ConversationTurnModelCompletion.ToolCall(
                        call,
                        USAGE
                );

        assertEquals(call, completion.call());
        assertSame(USAGE, completion.usage());
    }

    @Test
    void shouldRejectNullToolCall() {
        assertThrows(
                NullPointerException.class,
                () -> new ConversationTurnModelCompletion.ToolCall(
                        null,
                        USAGE
                )
        );
    }

    @Test
    void shouldRejectNullUsageForText() {
        assertThrows(
                NullPointerException.class,
                () -> new ConversationTurnModelCompletion.Text(
                        "Hello",
                        ChatModelFinishReason.STOP,
                        null
                )
        );
    }

    @Test
    void shouldRejectNullUsageForToolCall() throws Exception {
        ChatModelToolCall call = new ChatModelToolCall(
                "call-1",
                "create_ticket",
                new ObjectMapper().readTree(
                        "{\"title\":\"Server down\"}"
                )
        );

        assertThrows(
                NullPointerException.class,
                () -> new ConversationTurnModelCompletion.ToolCall(
                        call,
                        null
                )
        );
    }

    @Test
    void shouldRejectNullContent() {
        assertThrows(
                NullPointerException.class,
                () -> new ConversationTurnModelCompletion.Text(
                        null,
                        ChatModelFinishReason.STOP,
                        USAGE
                )
        );
    }

    @Test
    void shouldRejectNullFinishReason() {
        assertThrows(
                NullPointerException.class,
                () -> new ConversationTurnModelCompletion.Text(
                        "Hello",
                        null,
                        USAGE
                )
        );
    }
}
