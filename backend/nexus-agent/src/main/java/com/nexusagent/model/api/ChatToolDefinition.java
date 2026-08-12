package com.nexusagent.model.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.regex.Pattern;

public record ChatToolDefinition(
        String name,
        String description,
        JsonNode inputSchema
) {

    private static final Pattern NAME_PATTERN =
            Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public ChatToolDefinition {
        name = normalize(name, "name", 64);
        description = normalize(
                description,
                "description",
                500
        );

        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "name must use lowercase letters, numbers, and underscores"
            );
        }

        Objects.requireNonNull(
                inputSchema,
                "inputSchema must not be null"
        );

        if (!inputSchema.isObject()) {
            throw new IllegalArgumentException(
                    "inputSchema must be a JSON object"
            );
        }

        inputSchema = inputSchema.deepCopy();
    }

    @Override
    public JsonNode inputSchema() {
        return inputSchema.deepCopy();
    }

    private static String normalize(
            String value,
            String field,
            int maximumLength
    ) {
        if (value == null) {
            throw new IllegalArgumentException(
                    field + " must not be null"
            );
        }

        String normalized = value.trim();

        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank"
            );
        }

        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed "
                            + maximumLength
                            + " characters"
            );
        }

        return normalized;
    }
}