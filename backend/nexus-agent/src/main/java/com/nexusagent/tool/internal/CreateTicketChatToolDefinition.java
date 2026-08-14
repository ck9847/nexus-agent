package com.nexusagent.tool.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.model.api.ChatToolDefinition;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class CreateTicketChatToolDefinition {

    public static final String TOOL_NAME =
            "create_ticket";

    private final ChatToolDefinition definition;

    public CreateTicketChatToolDefinition(
            ObjectMapper objectMapper
    ) {
        Objects.requireNonNull(objectMapper);

        JsonNode schema;

        try {
            schema = objectMapper.readTree("""
                {
                  "type": "object",
                  "additionalProperties": false,
                  "properties": {
                    "title": {
                      "type": "string",
                      "minLength": 1,
                      "maxLength": 255,
                      "description":
                        "A concise support ticket title"
                    },
                    "description": {
                      "type": "string",
                      "minLength": 1,
                      "maxLength": 10000,
                      "description":
                        "A detailed description of the issue"
                    },
                    "priority": {
                      "type": "string",
                      "enum": [
                        "LOW",
                        "MEDIUM",
                        "HIGH",
                        "URGENT"
                      ]
                    }
                  },
                  "required": [
                    "title",
                    "description",
                    "priority"
                  ]
                }
                """);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to build create_ticket schema",
                    exception
            );
        }

        definition = new ChatToolDefinition(
                TOOL_NAME,
                "Create a support ticket for the current user",
                schema
        );
    }

    public ChatToolDefinition definition() {
        return definition;
    }
}