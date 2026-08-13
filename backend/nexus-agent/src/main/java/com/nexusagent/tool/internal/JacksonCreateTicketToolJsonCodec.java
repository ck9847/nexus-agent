package com.nexusagent.tool.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class JacksonCreateTicketToolJsonCodec
        implements CreateTicketToolJsonCodec {

    private final ObjectMapper objectMapper;

    public JacksonCreateTicketToolJsonCodec(
            ObjectMapper objectMapper
    ) {
        ObjectMapper configured =
                Objects.requireNonNull(
                        objectMapper,
                        "objectMapper must not be null"
                ).copy();

        configured.enable(
                DeserializationFeature
                        .FAIL_ON_UNKNOWN_PROPERTIES
        );
        configured.enable(
                DeserializationFeature
                        .FAIL_ON_TRAILING_TOKENS
        );

        this.objectMapper = configured;
    }

    @Override
    public CreateTicketToolArguments decodeArguments(
            String inputJson
    ) {
        return decode(
                inputJson,
                CreateTicketToolArguments.class
        );
    }

    @Override
    public String encodeOutput(
            CreateTicketToolOutput output
    ) {
        Objects.requireNonNull(
                output,
                "output must not be null"
        );

        try {
            return objectMapper.writeValueAsString(output);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize "
                            + "create_ticket tool output"
            );
        }
    }

    @Override
    public CreateTicketToolOutput decodeOutput(
            String outputJson
    ) {
        return decode(
                outputJson,
                CreateTicketToolOutput.class
        );
    }

    private <T> T decode(
            String json,
            Class<T> type
    ) {
        Objects.requireNonNull(
                json,
                "json must not be null"
        );

        if (json.isBlank()) {
            throw new IllegalArgumentException(
                    "json must not be blank"
            );
        }

        JsonNode node;

        try {
            node = objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "create_ticket tool JSON is malformed"
            );
        }

        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(
                    "create_ticket tool JSON "
                            + "must be an object"
            );
        }

        try {
            return objectMapper.treeToValue(node, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "create_ticket tool JSON does not "
                            + "match the expected schema"
            );
        }
    }
}
