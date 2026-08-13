package com.nexusagent.tool.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.regex.Pattern;

public record RegisterToolExecutionCommand(
        long conversationId,
        long agentId,
        long requestMessageId,
        String toolCallId,
        String toolName,
        JsonNode input,
        boolean approvalRequired,
        String traceId
) {

    private static final Pattern TOOL_NAME_PATTERN =
            Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public RegisterToolExecutionCommand {
        requirePositive(conversationId, "conversationId");
        requirePositive(agentId, "agentId");
        requirePositive(
                requestMessageId,
                "requestMessageId"
        );

        toolCallId = normalizeRequired(
                toolCallId,
                "toolCallId",
                128
        );

        toolName = normalizeRequired(
                toolName,
                "toolName",
                64
        );

        if (!TOOL_NAME_PATTERN.matcher(toolName).matches()) {
            throw new IllegalArgumentException(
                    "toolName must use lowercase letters, "
                            + "numbers, and underscores"
            );
        }

        Objects.requireNonNull(
                input,
                "input must not be null"
        );

        if (!input.isObject()) {
            throw new IllegalArgumentException(
                    "input must be a JSON object"
            );
        }

        input = input.deepCopy();
        traceId = normalizeOptional(
                traceId,
                "traceId",
                64
        );
    }

    @Override
    public JsonNode input() {
        return input.deepCopy();
    }

    private static void requirePositive(
            long value,
            String field
    ) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    field + " must be positive"
            );
        }
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

    private static String normalizeOptional(
            String value,
            String field,
            int maximumLength
    ) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();

        if (normalized.isBlank()) {
            return null;
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