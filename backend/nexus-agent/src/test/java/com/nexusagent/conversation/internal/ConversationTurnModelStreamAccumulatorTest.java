package com.nexusagent.conversation.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatModelStreamEvent;
import com.nexusagent.model.api.ChatTokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationTurnModelStreamAccumulatorTest {

    private static final ChatTokenUsage USAGE =
            new ChatTokenUsage(5, 7);

    private static final ChatModelStreamEvent.Completed STOP =
            new ChatModelStreamEvent.Completed(
                    ChatModelFinishReason.STOP,
                    USAGE
            );

    private static final ChatModelStreamEvent.Completed TOOL_CALLS =
            new ChatModelStreamEvent.Completed(
                    ChatModelFinishReason.TOOL_CALLS,
                    USAGE
            );

    private ConversationTurnModelStreamAccumulator accumulator;

    @BeforeEach
    void setUp() {
        accumulator = new ConversationTurnModelStreamAccumulator(
                new ObjectMapper()
        );
    }

    @Test
    void shouldAccumulateMultiFragmentText() {
        accumulator.onEvent(new ChatModelStreamEvent.TextDelta("Hello"));
        accumulator.onEvent(new ChatModelStreamEvent.TextDelta(" "));
        accumulator.onEvent(new ChatModelStreamEvent.TextDelta("world"));
        accumulator.onEvent(STOP);

        ConversationTurnModelCompletion completion =
                accumulator.requireCompletion();

        ConversationTurnModelCompletion.Text text =
                (ConversationTurnModelCompletion.Text) completion;

        assertEquals("Hello world", text.content());
        assertEquals(
                ChatModelFinishReason.STOP,
                text.finishReason()
        );
        assertSame(USAGE, text.usage());
    }

    @Test
    void shouldAccumulateMultiFragmentToolCall() {
        accumulator.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                0, "call", null, null
        ));
        accumulator.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                0, "-1", "create_", "{\"title\":"
        ));
        accumulator.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                0, null, "ticket", "\"Server down\"}"
        ));
        accumulator.onEvent(TOOL_CALLS);

        ConversationTurnModelCompletion completion =
                accumulator.requireCompletion();

        ConversationTurnModelCompletion.ToolCall tool =
                (ConversationTurnModelCompletion.ToolCall) completion;

        assertEquals("call-1", tool.call().id());
        assertEquals("create_ticket", tool.call().name());
        assertEquals(
                "Server down",
                tool.call().arguments().get("title").asText()
        );
        assertSame(USAGE, tool.usage());
    }

    @Test
    void shouldIgnoreNullFragments() {
        accumulator.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                0, "call-1", null, null
        ));
        accumulator.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                0, null, "create_ticket", "{\"title\":\"x\"}"
        ));
        accumulator.onEvent(TOOL_CALLS);

        ConversationTurnModelCompletion completion =
                accumulator.requireCompletion();

        ConversationTurnModelCompletion.ToolCall tool =
                (ConversationTurnModelCompletion.ToolCall) completion;

        assertEquals("call-1", tool.call().id());
        assertEquals("create_ticket", tool.call().name());
    }

    @Test
    void shouldRejectUnsupportedToolName() {
        accumulator.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                0, "call-1", "other_tool", "{\"a\":1}"
        ));
        accumulator.onEvent(TOOL_CALLS);

        assertMalformed(() ->
                accumulator.requireCompletion()
        );
    }

    @Test
    void shouldRejectNonZeroIndex() {
        assertMalformed(() -> accumulator.onEvent(
                new ChatModelStreamEvent.ToolCallDelta(
                        1, "call-1", "create_ticket", "{\"a\":1}"
                )
        ));
    }

    @Test
    void shouldRejectToolCallAfterText() {
        accumulator.onEvent(new ChatModelStreamEvent.TextDelta("Hi"));

        assertMalformed(() -> accumulator.onEvent(
                new ChatModelStreamEvent.ToolCallDelta(
                        0, "call-1", "create_ticket", "{\"a\":1}"
                )
        ));
    }

    @Test
    void shouldRejectTextAfterToolCall() {
        accumulator.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                0, "call-1", "create_ticket", "{\"a\":1}"
        ));

        assertMalformed(() -> accumulator.onEvent(
                new ChatModelStreamEvent.TextDelta("Hi")
        ));
    }

    @Test
    void shouldRejectToolCallWithNonToolCallsFinish() {
        accumulator.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                0, "call-1", "create_ticket", "{\"a\":1}"
        ));
        accumulator.onEvent(STOP);

        assertMalformed(() ->
                accumulator.requireCompletion()
        );
    }

    @Test
    void shouldRejectTextWithToolCallsFinish() {
        accumulator.onEvent(new ChatModelStreamEvent.TextDelta("Hi"));
        accumulator.onEvent(TOOL_CALLS);

        assertMalformed(() ->
                accumulator.requireCompletion()
        );
    }

    @Test
    void shouldRejectMissingCallId() {
        accumulator.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                0, null, "create_ticket", "{\"a\":1}"
        ));
        accumulator.onEvent(TOOL_CALLS);

        assertMalformed(() ->
                accumulator.requireCompletion()
        );
    }

    @Test
    void shouldRejectMissingName() {
        accumulator.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                0, "call-1", null, "{\"a\":1}"
        ));
        accumulator.onEvent(TOOL_CALLS);

        assertMalformed(() ->
                accumulator.requireCompletion()
        );
    }

    @Test
    void shouldRejectMissingArguments() {
        accumulator.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                0, "call-1", "create_ticket", null
        ));
        accumulator.onEvent(TOOL_CALLS);

        assertMalformed(() ->
                accumulator.requireCompletion()
        );
    }

    @Test
    void shouldRejectNonObjectArguments() {
        accumulator.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                0, "call-1", "create_ticket", "[1,2]"
        ));
        accumulator.onEvent(TOOL_CALLS);

        assertMalformed(() ->
                accumulator.requireCompletion()
        );
    }

    @Test
    void shouldRejectMalformedArguments() {
        accumulator.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                0, "call-1", "create_ticket", "{\"title\":"
        ));
        accumulator.onEvent(TOOL_CALLS);

        assertMalformed(() ->
                accumulator.requireCompletion()
        );
    }

    @Test
    void shouldRejectTrailingTokensInArguments() {
        accumulator.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                0, "call-1", "create_ticket", "{\"a\":1} garbage"
        ));
        accumulator.onEvent(TOOL_CALLS);

        assertMalformed(() ->
                accumulator.requireCompletion()
        );
    }

    @Test
    void shouldRejectOversizedText() {
        accumulator.onEvent(new ChatModelStreamEvent.TextDelta(
                "a".repeat(50_000)
        ));

        assertMalformed(() -> accumulator.onEvent(
                new ChatModelStreamEvent.TextDelta("b")
        ));
    }

    @Test
    void shouldRejectOversizedCallId() {
        assertMalformed(() -> accumulator.onEvent(
                new ChatModelStreamEvent.ToolCallDelta(
                        0, "a".repeat(129), null, null
                )
        ));
    }

    @Test
    void shouldRejectOversizedName() {
        assertMalformed(() -> accumulator.onEvent(
                new ChatModelStreamEvent.ToolCallDelta(
                        0, null, "a".repeat(65), null
                )
        ));
    }

    @Test
    void shouldRejectOversizedArgumentBytes() {
        String huge = "{\"description\":\""
                + "a".repeat(70_000)
                + "\"}";

        assertMalformed(() -> accumulator.onEvent(
                new ChatModelStreamEvent.ToolCallDelta(
                        0, "call-1", "create_ticket", huge
                )
        ));
    }

    @Test
    void shouldRejectMissingCompleted() {
        accumulator.onEvent(new ChatModelStreamEvent.TextDelta("Hi"));

        assertMalformed(() ->
                accumulator.requireCompletion()
        );
    }

    @Test
    void shouldRejectDuplicateCompleted() {
        accumulator.onEvent(new ChatModelStreamEvent.TextDelta("Hi"));
        accumulator.onEvent(STOP);

        assertMalformed(() -> accumulator.onEvent(STOP));
    }

    @Test
    void shouldRejectEventAfterCompleted() {
        accumulator.onEvent(new ChatModelStreamEvent.TextDelta("Hi"));
        accumulator.onEvent(STOP);

        assertMalformed(() -> accumulator.onEvent(
                new ChatModelStreamEvent.TextDelta("more")
        ));
    }

    @Test
    void shouldRejectNullEvent() {
        assertMalformed(
                () -> accumulator.onEvent(null)
        );
    }

    @Test
    void shouldRejectEmptyCompletion() {
        accumulator.onEvent(STOP);

        assertMalformed(() ->
                accumulator.requireCompletion()
        );
    }

    @Test
    void shouldDefensivelyCopyToolArguments() {
        accumulator.onEvent(new ChatModelStreamEvent.ToolCallDelta(
                0, "call-1", "create_ticket", "{\"title\":\"x\"}"
        ));
        accumulator.onEvent(TOOL_CALLS);

        ConversationTurnModelCompletion completion =
                accumulator.requireCompletion();

        ConversationTurnModelCompletion.ToolCall tool =
                (ConversationTurnModelCompletion.ToolCall) completion;

        JsonNode arguments = tool.call().arguments();

        ((ObjectNode) arguments).put("title", "mutated");

        JsonNode again = tool.call().arguments();

        assertEquals("x", again.get("title").asText());
    }

    private static void assertMalformed(Runnable action) {
        ChatModelException exception =
                assertThrows(
                        ChatModelException.class,
                        action::run
                );

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                exception.category()
        );
        assertEquals(
                "Chat model stream is malformed",
                exception.getMessage()
        );
    }
}
