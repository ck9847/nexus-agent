package com.nexusagent.ticket.internal;

import com.nexusagent.ticket.domain.TicketPriority;

import java.util.Objects;

public final class TicketCreationNormalizer {

    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MAX_DESCRIPTION_LENGTH = 10_000;

    private TicketCreationNormalizer() {
    }

    public static NormalizedTicketCreation normalize(
            String title,
            String description,
            TicketPriority priority
    ) {
        return new NormalizedTicketCreation(
                normalizeRequired(
                        title,
                        "title",
                        MAX_TITLE_LENGTH
                ),
                normalizeRequired(
                        description,
                        "description",
                        MAX_DESCRIPTION_LENGTH
                ),
                Objects.requireNonNull(
                        priority,
                        "priority must not be null"
                )
        );
    }

    private static String normalizeRequired(
            String value,
            String fieldName,
            int maxLength
    ) {
        if (value == null) {
            throw new IllegalArgumentException(
                    fieldName + " must not be null"
            );
        }

        String normalized = value.trim();

        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank"
            );
        }

        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    fieldName + " must not exceed "
                            + maxLength + " characters"
            );
        }

        return normalized;
    }
}

record NormalizedTicketCreation(
        String title,
        String description,
        TicketPriority priority
) {
}
