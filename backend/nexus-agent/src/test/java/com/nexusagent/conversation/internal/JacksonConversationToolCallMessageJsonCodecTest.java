package com.nexusagent.conversation.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusagent.model.api.ChatModelToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonConversationToolCallMessageJsonCodecTest {

    private final JacksonConversationToolCallMessageJsonCodec
            codec =
            new JacksonConversationToolCallMessageJsonCodec(
                    new ObjectMapper()
            );

    @Test
    void shouldRoundTripNestedArguments() throws Exception {
        ChatModelToolCall toolCall = new ChatModelToolCall(
                "call_123",
                "create_ticket",
                new ObjectMapper().readTree(
                        """
                        {
                            "title": "Payment failed",
                            "description": "Cannot pay",
                            "priority": "HIGH",
                            "meta": {"a": 1, "b": [1, 2, 3]}
                        }
                        """
                )
        );

        String json = codec.encode(toolCall);

        ChatModelToolCall decoded = codec.decode(json);

        assertEquals(toolCall.id(), decoded.id());
        assertEquals(toolCall.name(), decoded.name());
        assertEquals(
                toolCall.arguments(),
                decoded.arguments()
        );
    }

    @Test
    void shouldProduceStableFieldOrder() throws Exception {
        ChatModelToolCall toolCall = new ChatModelToolCall(
                "call_123",
                "create_ticket",
                new ObjectMapper().readTree(
                        "{\"title\":\"Payment failed\"}"
                )
        );

        String json = codec.encode(toolCall);

        int idIndex = json.indexOf("\"id\"");
        int nameIndex = json.indexOf("\"name\"");
        int argumentsIndex = json.indexOf("\"arguments\"");

        assertEquals(
                "{\"id\":\"call_123\","
                        + "\"name\":\"create_ticket\","
                        + "\"arguments\":{\"title\":"
                        + "\"Payment failed\"}}",
                json
        );

        assertEquals(idIndex, 1);
        assertTrue(idIndex < nameIndex);
        assertTrue(nameIndex < argumentsIndex);
    }

    @Test
    void shouldDefensivelyCopyOnEncode() throws Exception {
        ObjectNode arguments = (ObjectNode)
                new ObjectMapper().readTree(
                        "{\"title\":\"Payment failed\"}"
                );

        ChatModelToolCall toolCall = new ChatModelToolCall(
                "call_123",
                "create_ticket",
                arguments
        );

        String first = codec.encode(toolCall);

        arguments.put("title", "mutated");

        String second = codec.encode(toolCall);

        assertEquals(first, second);
    }

    @Test
    void shouldDefensivelyCopyOnDecode() {
        ChatModelToolCall decoded = codec.decode(
                """
                {
                    "id": "call_123",
                    "name": "create_ticket",
                    "arguments": {"title": "Payment failed"}
                }
                """
        );

        ObjectNode arguments = (ObjectNode) decoded.arguments();

        arguments.put("title", "mutated");

        assertEquals(
                "Payment failed",
                decoded.arguments().get("title").asText()
        );

        assertNotSame(
                arguments,
                decoded.arguments()
        );
    }

    @Test
    void shouldRejectNullToolCallOnEncode() {
        assertThrows(
                NullPointerException.class,
                () -> codec.encode(null)
        );
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "   ",
            "[]",
            "[1,2]",
            "42",
            "\"text\"",
            "null",
            "{\"id\":",
            "not json",
            """
            {"id":"call_1","name":"create_ticket","arguments":{}}
            garbage
            """,
            """
            {"id":"call_1","name":"create_ticket","arguments":{}}
            {"id":"call_2","name":"create_ticket","arguments":{}}
            """
    })
    void shouldRejectNullBlankOrMalformedJson(String json) {
        if (json == null) {
            assertThrows(
                    NullPointerException.class,
                    () -> codec.decode(json)
            );
            return;
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(json)
        );
    }

    @Test
    void shouldRejectUnknownFields() {
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(
                        """
                        {
                            "id": "call_1",
                            "name": "create_ticket",
                            "arguments": {},
                            "secret": "boom"
                        }
                        """
                )
        );
    }

    @Test
    void shouldRejectOversizedJson() {
        String huge = "{\"id\":\"call_1\","
                + "\"name\":\"create_ticket\","
                + "\"arguments\":{\"title\":\""
                + "a".repeat(60_000)
                + "\"}}";

        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(huge)
        );
    }
}
