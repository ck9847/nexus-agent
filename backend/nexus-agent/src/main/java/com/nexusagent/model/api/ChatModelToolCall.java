package com.nexusagent.model.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.regex.Pattern;

public record ChatModelToolCall(
        String id,
        String name,
        JsonNode arguments
) {

    private static final Pattern NAME_PATTERN =
            Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public ChatModelToolCall {
        id = normalizeRequired(id, "id", 128);
        name = normalizeRequired(name, "name", 64);

        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "name must use lowercase letters, numbers, and underscores"
            );
        }

        Objects.requireNonNull(
                arguments,
                "arguments must not be null"
        );

        if (!arguments.isObject()) {
            throw new IllegalArgumentException(
                    "arguments must be a JSON object"
            );
        }

        arguments = arguments.deepCopy();
    }

    @Override
    public JsonNode arguments() {
        return arguments.deepCopy();
    }

    private static String normalizeRequired(
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