package com.nexusagent.tool.api;

import com.nexusagent.tool.domain.ToolExecutionStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record RegisterToolExecutionResult(
        long toolExecutionId,
        String idempotencyKey,
        ToolExecutionStatus status,
        boolean newlyCreated,
        Instant createdAt
) {

    private static final Pattern KEY_PATTERN =
            Pattern.compile("tool:v1:[0-9a-f]{64}");

    public RegisterToolExecutionResult {
        if (toolExecutionId <= 0) {
            throw new IllegalArgumentException(
                    "toolExecutionId must be positive"
            );
        }

        if (idempotencyKey == null
                || !KEY_PATTERN.matcher(
                idempotencyKey
        ).matches()) {
            throw new IllegalArgumentException(
                    "idempotencyKey has invalid format"
            );
        }

        Objects.requireNonNull(
                status,
                "status must not be null"
        );
        Objects.requireNonNull(
                createdAt,
                "createdAt must not be null"
        );
    }
}