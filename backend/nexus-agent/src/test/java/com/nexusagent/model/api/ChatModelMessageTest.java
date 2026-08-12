package com.nexusagent.model.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatModelMessageTest {

    private static ChatModelToolCall toolCall() {
        return new ChatModelToolCall(
                "call_1",
                "search_docs",
                JsonNodeFactory.instance.objectNode()
                        .put("query", "hello")
        );
    }

    @Test
    void shouldAcceptUserText() {
        ChatModelMessage message =
                new ChatModelMessage(
                        ChatModelRole.USER,
                        "Hello",
                        List.of(),
                        null
                );

        assertEquals(ChatModelRole.USER, message.role());
        assertEquals("Hello", message.content());
        assertTrue(message.toolCalls().isEmpty());
        assertNull(message.toolCallId());
    }

    @Test
    void shouldAcceptAssistantText() {
        ChatModelMessage message =
                new ChatModelMessage(
                        ChatModelRole.ASSISTANT,
                        "Hello back",
                        List.of(),
                        null
                );

        assertEquals(ChatModelRole.ASSISTANT, message.role());
        assertEquals("Hello back", message.content());
        assertTrue(message.toolCalls().isEmpty());
        assertNull(message.toolCallId());
    }

    @Test
    void shouldAcceptAssistantToolCalls() {
        ChatModelMessage message =
                new ChatModelMessage(
                        ChatModelRole.ASSISTANT,
                        null,
                        List.of(toolCall()),
                        null
                );

        assertNull(message.content());
        assertEquals(1, message.toolCalls().size());
        assertEquals(
                "search_docs",
                message.toolCalls().get(0).name()
        );
    }

    @Test
    void shouldAcceptToolResult() {
        ChatModelMessage message =
                new ChatModelMessage(
                        ChatModelRole.TOOL,
                        "Search result",
                        List.of(),
                        "call_1"
                );

        assertEquals(ChatModelRole.TOOL, message.role());
        assertEquals("Search result", message.content());
        assertTrue(message.toolCalls().isEmpty());
        assertEquals("call_1", message.toolCallId());
    }

    @Test
    void shouldRejectUserWithToolCalls() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new ChatModelMessage(
                                ChatModelRole.USER,
                                "Hello",
                                List.of(toolCall()),
                                null
                        )
                );

        assertEquals(
                "message role does not allow tool calls",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectToolWithoutToolCallId() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new ChatModelMessage(
                                ChatModelRole.TOOL,
                                "Search result",
                                List.of(),
                                null
                        )
                );

        assertEquals(
                "tool message must contain toolCallId",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectAssistantWithoutContentOrToolCalls() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new ChatModelMessage(
                                ChatModelRole.ASSISTANT,
                                null,
                                List.of(),
                                null
                        )
                );

        assertEquals(
                "assistant message must contain content "
                        + "or tool calls",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectBlankContent() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChatModelMessage(
                        ChatModelRole.USER,
                        "   ",
                        List.of(),
                        null
                )
        );
    }

    @Test
    void shouldRejectBlankToolCallId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChatModelMessage(
                        ChatModelRole.TOOL,
                        "Search result",
                        List.of(),
                        "   "
                )
        );
    }

    @Test
    void shouldDefensivelyCopyToolCallsList() {
        List<ChatModelToolCall> mutableToolCalls =
                new ArrayList<>();
        mutableToolCalls.add(toolCall());

        ChatModelMessage message =
                new ChatModelMessage(
                        ChatModelRole.ASSISTANT,
                        null,
                        mutableToolCalls,
                        null
                );

        // 修改传入的原始列表不影响内部副本。
        mutableToolCalls.clear();

        assertEquals(1, message.toolCalls().size());

        // 内部副本不可修改。
        assertThrows(
                UnsupportedOperationException.class,
                () -> message.toolCalls().add(toolCall())
        );
    }

    @Test
    void shouldDefensivelyCopyToolCallArgumentsJson() {
        ObjectNode arguments =
                JsonNodeFactory.instance.objectNode();
        arguments.put("query", "hello");

        ChatModelToolCall toolCall =
                new ChatModelToolCall(
                        "call_1",
                        "search_docs",
                        arguments
                );

        // 修改传入的原始 JsonNode 不影响内部副本。
        arguments.put("query", "mutated");

        assertEquals(
                "hello",
                toolCall.arguments().get("query").asText()
        );

        // 修改访问器返回的副本不影响后续读取。
        JsonNode firstRead = toolCall.arguments();
        ((ObjectNode) firstRead).put(
                "query",
                "mutated-again"
        );

        assertEquals(
                "hello",
                toolCall.arguments().get("query").asText()
        );
    }
}
