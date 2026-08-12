package com.nexusagent.model.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatModelStreamEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonOpenAiChatCompletionStreamDecoderTest {

    private static final int MAX_EVENT_CHARACTERS = 1_000_000;

    private static final String TEXT_SSE = """
            data: {"choices":[{"index":0,"delta":{"content":"Hello"}}]}

            data: {"choices":[{"index":0,"delta":{"content":" world"}}]}

            data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2}}

            data: [DONE]
            """;

    private static final String TOOL_CALL_SSE = """
            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"search","arguments":""}}]}}]}

            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"function":{"arguments":"{\\"q\\":\\"a\\"}"}}]}}]}

            data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

            data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2}}

            data: [DONE]
            """;

    private final JacksonOpenAiChatCompletionStreamDecoder decoder =
            new JacksonOpenAiChatCompletionStreamDecoder(
                    new ObjectMapper()
            );

    @Test
    void shouldEmitTextDeltaPerChunk() {
        List<ChatModelStreamEvent> events = decode(TEXT_SSE);

        ChatModelStreamEvent.TextDelta first = assertInstanceOf(
                ChatModelStreamEvent.TextDelta.class,
                events.get(0)
        );
        assertEquals("Hello", first.text());

        ChatModelStreamEvent.TextDelta second = assertInstanceOf(
                ChatModelStreamEvent.TextDelta.class,
                events.get(1)
        );
        assertEquals(" world", second.text());

        assertCompleted(events, ChatModelFinishReason.STOP, 1, 2);
    }

    @Test
    void shouldKeepWhitespaceOnlyTextDelta() {
        List<ChatModelStreamEvent> events = decode(
                TEXT_SSE.replace("Hello", "   ")
        );

        ChatModelStreamEvent.TextDelta delta = assertInstanceOf(
                ChatModelStreamEvent.TextDelta.class,
                events.get(0)
        );
        assertEquals("   ", delta.text());
    }

    @Test
    void shouldIgnoreEmptyTextDelta() {
        List<ChatModelStreamEvent> events = decode("""
                data: {"choices":[{"index":0,"delta":{"content":""}}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2}}

                data: [DONE]
                """);

        assertEquals(1, events.size());
        assertInstanceOf(
                ChatModelStreamEvent.Completed.class,
                events.get(0)
        );
    }

    @Test
    void shouldEmitToolCallFragmentsByIndex() {
        List<ChatModelStreamEvent> events = decode(TOOL_CALL_SSE);

        ChatModelStreamEvent.ToolCallDelta first =
                assertInstanceOf(
                        ChatModelStreamEvent.ToolCallDelta.class,
                        events.get(0)
                );
        assertEquals(0, first.index());
        assertEquals("call_1", first.callIdFragment());
        assertEquals("search", first.nameFragment());
        assertNull(first.argumentsFragment());

        ChatModelStreamEvent.ToolCallDelta second =
                assertInstanceOf(
                        ChatModelStreamEvent.ToolCallDelta.class,
                        events.get(1)
                );
        assertEquals(1, second.index());
        assertNull(second.callIdFragment());
        assertNull(second.nameFragment());
        assertEquals("{\"q\":\"a\"}", second.argumentsFragment());

        assertCompleted(
                events,
                ChatModelFinishReason.TOOL_CALLS,
                1,
                2
        );
    }

    @Test
    void shouldMapAllFinishReasons() {
        assertFinishReason(
                "stop",
                ChatModelFinishReason.STOP
        );
        assertFinishReason(
                "tool_calls",
                ChatModelFinishReason.TOOL_CALLS
        );
        assertFinishReason(
                "length",
                ChatModelFinishReason.LENGTH
        );
        assertFinishReason(
                "content_filter",
                ChatModelFinishReason.CONTENT_FILTER
        );
    }

    @Test
    void shouldMapUsage() {
        List<ChatModelStreamEvent> events = decode("""
                data: {"choices":[{"index":0,"delta":{"content":"Hi"}}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5}}

                data: [DONE]
                """);

        assertCompleted(events, ChatModelFinishReason.STOP, 10, 5);
    }

    @Test
    void shouldCompleteOnlyOnce() {
        List<ChatModelStreamEvent> events = decode("""
                data: {"choices":[{"index":0,"delta":{"content":"Hi"}}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2}}

                data: [DONE]

                data: [DONE]
                """);

        long completed = events.stream()
                .filter(
                        ChatModelStreamEvent.Completed.class
                                ::isInstance
                )
                .count();

        assertEquals(1, completed);
    }

    @Test
    void shouldRejectMalformedJson() {
        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> decode("""
                        data: {"choices":[{"index":0,"delta":{"content":"Hi"}}]}

                        data: not-json

                        data: [DONE]
                        """)
        );

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                exception.category()
        );
        assertFalse(exception.retryable());
    }

    @Test
    void shouldRejectMultipleChoices() {
        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> decode("""
                        data: {"choices":[{"index":0,"delta":{"content":"a"}},{"index":1,"delta":{"content":"b"}}]}

                        data: [DONE]
                        """)
        );

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                exception.category()
        );
        assertEquals(
                "Chat model event contains multiple choices",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectNonZeroChoiceIndex() {
        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> decode("""
                        data: {"choices":[{"index":1,"delta":{"content":"a"}}]}

                        data: [DONE]
                        """)
        );

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                exception.category()
        );
        assertEquals(
                "Chat model event contains an invalid choice",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectInvalidTokenCount() {
        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> decode("""
                        data: {"choices":[],"usage":{"prompt_tokens":-1,"completion_tokens":5}}

                        data: [DONE]
                        """)
        );

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                exception.category()
        );
        assertEquals(
                "Chat model usage contains an invalid token count",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectDoneWithoutUsage() {
        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> decode("""
                        data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                        data: [DONE]
                        """)
        );

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                exception.category()
        );
        assertEquals(
                "Chat model stream completed without finish reason or usage",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectDoneWithoutFinishReason() {
        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> decode("""
                        data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2}}

                        data: [DONE]
                        """)
        );

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                exception.category()
        );
        assertEquals(
                "Chat model stream completed without finish reason or usage",
                exception.getMessage()
        );
    }

    @Test
    void shouldThrowStreamInterruptedWithoutDone() {
        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> decode("""
                        data: {"choices":[{"index":0,"delta":{"content":"Hello"}}]}

                        data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                        data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2}}
                        """)
        );

        assertEquals(
                ChatModelErrorCategory.STREAM_INTERRUPTED,
                exception.category()
        );
        assertTrue(exception.retryable());
    }

    @Test
    void shouldRejectOversizedEvent() {
        String oversized = "data: "
                + "x".repeat(MAX_EVENT_CHARACTERS + 1)
                + "\n\n";

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> decode(oversized)
        );

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                exception.category()
        );
        assertEquals(
                "Chat model stream event is too large",
                exception.getMessage()
        );
    }

    @Test
    void shouldSupportMultilineDataEvent() {
        String input =
                "data: {\n"
                        + "data:   \"choices\": [\n"
                        + "data:     {\"index\": 0, \"delta\": {\"content\": \"Hi\"}}\n"
                        + "data:   ]\n"
                        + "data: }\n\n"
                        + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2}}\n\n"
                        + "data: [DONE]\n\n";

        List<ChatModelStreamEvent> events = decode(input);

        ChatModelStreamEvent.TextDelta delta = assertInstanceOf(
                ChatModelStreamEvent.TextDelta.class,
                events.get(0)
        );
        assertEquals("Hi", delta.text());

        assertCompleted(events, ChatModelFinishReason.STOP, 1, 2);
    }

    @Test
    void shouldIgnoreCommentLines() {
        List<ChatModelStreamEvent> events = decode("""
                : keep-alive

                data: {"choices":[{"index":0,"delta":{"content":"Hi"}}]}

                : heartbeat

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2}}

                data: [DONE]
                """);

        assertEquals(2, events.size());

        ChatModelStreamEvent.TextDelta delta = assertInstanceOf(
                ChatModelStreamEvent.TextDelta.class,
                events.get(0)
        );
        assertEquals("Hi", delta.text());

        assertCompleted(events, ChatModelFinishReason.STOP, 1, 2);
    }

    private void assertFinishReason(
            String raw,
            ChatModelFinishReason expected
    ) {
        List<ChatModelStreamEvent> events = decode("""
                data: {"choices":[{"index":0,"delta":{},"finish_reason":"%s"}]}

                data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2}}

                data: [DONE]
                """.formatted(raw));

        assertCompleted(events, expected, 1, 2);
    }

    private List<ChatModelStreamEvent> decode(String input) {
        List<ChatModelStreamEvent> events = new ArrayList<>();

        decoder.decode(
                new ByteArrayInputStream(
                        input.getBytes(StandardCharsets.UTF_8)
                ),
                events::add
        );

        return events;
    }

    private static void assertCompleted(
            List<ChatModelStreamEvent> events,
            ChatModelFinishReason finishReason,
            int promptTokens,
            int completionTokens
    ) {
        ChatModelStreamEvent.Completed completed =
                events.stream()
                        .filter(
                                ChatModelStreamEvent.Completed
                                        .class::isInstance
                        )
                        .map(
                                ChatModelStreamEvent.Completed
                                        .class::cast
                        )
                        .findFirst()
                        .orElseThrow();

        assertEquals(finishReason, completed.finishReason());
        assertEquals(
                promptTokens,
                completed.usage().promptTokens()
        );
        assertEquals(
                completionTokens,
                completed.usage().completionTokens()
        );
    }
}
