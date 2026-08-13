package com.nexusagent.tool.internal;

import java.time.Instant;
import java.util.Objects;

public record CreateTicketToolFailure(
        String errorCode,
        String safeMessage,
        Instant failedAt
) {

    public CreateTicketToolFailure {
        if (errorCode == null
                || errorCode.isBlank()) {
            throw new IllegalArgumentException(
                    "errorCode must not be blank"
            );
        }

        Objects.requireNonNull(
                safeMessage,
                "safeMessage must not be null"
        );

        Objects.requireNonNull(
                failedAt,
                "failedAt must not be null"
        );
    }
}
