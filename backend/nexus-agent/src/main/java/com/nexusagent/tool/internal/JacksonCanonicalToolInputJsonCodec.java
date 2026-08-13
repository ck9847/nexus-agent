package com.nexusagent.tool.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public final class JacksonCanonicalToolInputJsonCodec
        implements ToolInputJsonCodec {

    private static final int MAX_INPUT_BYTES = 64 * 1024;

    private final ObjectMapper objectMapper;
    private final ObjectWriter writer;

    public JacksonCanonicalToolInputJsonCodec(
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

        this.objectMapper = configured;
        this.writer = configured.writerFor(JsonNode.class);
    }

    @Override
    public String encode(JsonNode input) {
        Objects.requireNonNull(
                input,
                "input must not be null"
        );

        if (!input.isObject()) {
            throw new IllegalArgumentException(
                    "input must be a JSON object"
            );
        }

        JsonNode canonical = canonicalize(input);

        try {
            byte[] bytes = writer.writeValueAsBytes(canonical);

            if (bytes.length > MAX_INPUT_BYTES) {
                throw new IllegalArgumentException(
                        "input must not exceed "
                                + MAX_INPUT_BYTES
                                + " UTF-8 bytes"
                );
            }

            return new String(bytes, StandardCharsets.UTF_8);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize tool input",
                    exception
            );
        }
    }

    @Override
    public JsonNode decode(String json) {
        Objects.requireNonNull(
                json,
                "json must not be null"
        );

        int inputBytes =
                json.getBytes(StandardCharsets.UTF_8).length;

        if (inputBytes > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException(
                    "input must not exceed "
                            + MAX_INPUT_BYTES
                            + " UTF-8 bytes"
            );
        }

        JsonNode parsed;

        try {
            parsed = objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Failed to parse tool input JSON",
                    exception
            );
        }

        if (parsed == null || !parsed.isObject()) {
            throw new IllegalArgumentException(
                    "tool input must be a JSON object"
            );
        }

        return canonicalize(parsed);
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result =
                    objectMapper.createObjectNode();

            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);

            for (String name : names) {
                result.set(
                        name,
                        canonicalize(node.get(name))
                );
            }

            return result;
        }

        if (node.isArray()) {
            ArrayNode result =
                    objectMapper.createArrayNode();

            for (JsonNode element : node) {
                result.add(canonicalize(element));
            }

            return result;
        }

        return node.deepCopy();
    }
}
