package com.nexusagent.tool.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.model.api.ChatToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateTicketChatToolDefinitionTest {

    private final CreateTicketChatToolDefinition factory =
            new CreateTicketChatToolDefinition(
                    new ObjectMapper()
            );

    @Test
    void shouldDescribeCreateTicketTool() {
        ChatToolDefinition definition =
                factory.definition();

        assertEquals("create_ticket", definition.name());
        assertFalse(definition.description().isBlank());
    }

    @Test
    void shouldExposeStrictObjectSchema() {
        JsonNode schema = factory.definition().inputSchema();

        assertEquals("object", schema.get("type").asText());
        assertFalse(
                schema.get("additionalProperties").asBoolean()
        );
    }

    @Test
    void shouldRequireExactlyTitleDescriptionPriority() {
        JsonNode required =
                factory.definition().inputSchema().get("required");

        List<String> fields = StreamSupport.stream(
                        required.spliterator(),
                        false
                )
                .map(JsonNode::asText)
                .sorted()
                .toList();

        assertEquals(
                List.of("description", "priority", "title"),
                fields
        );
        assertEquals(3, required.size());
    }

    @Test
    void shouldConstrainTitleAndDescriptionLengths() {
        JsonNode properties = factory.definition()
                .inputSchema()
                .get("properties");

        assertEquals(
                255,
                properties.get("title").get("maxLength").asInt()
        );
        assertEquals(
                10000,
                properties.get("description")
                        .get("maxLength")
                        .asInt()
        );
    }

    @Test
    void shouldAllowExactlySupportedPriorities() {
        JsonNode priority = factory.definition()
                .inputSchema()
                .get("properties")
                .get("priority");

        List<String> values = StreamSupport.stream(
                        priority.get("enum").spliterator(),
                        false
                )
                .map(JsonNode::asText)
                .toList();

        assertEquals(
                List.of("LOW", "MEDIUM", "HIGH", "URGENT"),
                values
        );
    }

    @Test
    void shouldDefensivelyCopyInputSchema() {
        JsonNode first = factory.definition().inputSchema();
        JsonNode second = factory.definition().inputSchema();

        assertNotSame(first, second);

        ((com.fasterxml.jackson.databind.node.ObjectNode) first)
                .put("type", "array");

        JsonNode third = factory.definition().inputSchema();

        assertEquals("object", third.get("type").asText());
        assertEquals("object", second.get("type").asText());
    }
}
