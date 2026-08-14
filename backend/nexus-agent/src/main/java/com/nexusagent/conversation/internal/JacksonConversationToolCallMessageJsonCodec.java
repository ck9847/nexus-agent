package com.nexusagent.conversation.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusagent.model.api.ChatModelToolCall;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class JacksonConversationToolCallMessageJsonCodec
        implements ConversationToolCallMessageJsonCodec {

    private static final int MAX_JSON_LENGTH = 50_000;

    private final ObjectMapper objectMapper;

    public JacksonConversationToolCallMessageJsonCodec(
            ObjectMapper objectMapper
    ) {
        ObjectMapper configured =
                Objects.requireNonNull(
                        objectMapper,
                        "objectMapper must not be null"
                ).copy();

        configured.enable(
                DeserializationFeature
                        .FAIL_ON_TRAILING_TOKENS
        );
        configured.enable(
                DeserializationFeature
                        .FAIL_ON_UNKNOWN_PROPERTIES
        );

        this.objectMapper = configured;
    }

    @Override
    public String encode(ChatModelToolCall toolCall) {
        Objects.requireNonNull(
                toolCall,
                "toolCall must not be null"
        );

        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", toolCall.id());
        node.put("name", toolCall.name());
        node.set("arguments", toolCall.arguments());

        String json;

        try {
            json = objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize tool call message"
            );
        }

        if (json.length() > MAX_JSON_LENGTH) {
            throw new IllegalArgumentException(
                    "tool call message must not exceed "
                            + MAX_JSON_LENGTH
                            + " characters"
            );
        }

        return json;
    }

    @Override
    public ChatModelToolCall decode(String json) {
        Objects.requireNonNull(
                json,
                "json must not be null"
        );

        if (json.isBlank()) {
            throw new IllegalArgumentException(
                    "json must not be blank"
            );
        }

        if (json.length() > MAX_JSON_LENGTH) {
            throw new IllegalArgumentException(
                    "tool call message must not exceed "
                            + MAX_JSON_LENGTH
                            + " characters"
            );
        }

        JsonNode node;

        try {
            node = objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "tool call message JSON is malformed"
            );
        }

        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(
                    "tool call message JSON "
                            + "must be an object"
            );
        }

        try {
            return objectMapper.treeToValue(
                    node,
                    ChatModelToolCall.class
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "tool call message JSON does not "
                            + "match the expected schema"
            );
        }
    }
}
