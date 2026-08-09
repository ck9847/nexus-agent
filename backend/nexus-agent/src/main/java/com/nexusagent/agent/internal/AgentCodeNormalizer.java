package com.nexusagent.agent.internal;

import java.util.Locale;
import java.util.regex.Pattern;

final class AgentCodeNormalizer {

    private static final int MAX_CODE_LENGTH = 64;

    private static final Pattern CODE_PATTERN =
            Pattern.compile(
                    "^[a-z][a-z0-9-]{2,63}$"
            );

    private AgentCodeNormalizer() {
    }

    static String normalize(String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "code must not be null"
            );
        }

        String normalized = value.trim();

        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    "code must not be blank"
            );
        }

        if (normalized.length() > MAX_CODE_LENGTH) {
            throw new IllegalArgumentException(
                    "code must not exceed "
                            + MAX_CODE_LENGTH
                            + " characters"
            );
        }

        normalized = normalized.toLowerCase(
                Locale.ROOT
        );

        if (!CODE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "code must be 3 to 64 lowercase "
                            + "letters, digits or hyphens and "
                            + "start with a letter"
            );
        }

        return normalized;
    }
}