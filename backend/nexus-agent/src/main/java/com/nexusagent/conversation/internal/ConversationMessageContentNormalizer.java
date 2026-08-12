package com.nexusagent.conversation.internal;

final class ConversationMessageContentNormalizer {

    static final int MAX_CONTENT_LENGTH = 50_000;

    private ConversationMessageContentNormalizer() {
    }

    static String normalize(String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "content must not be null"
            );
        }

        String normalized = value.trim();

        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    "content must not be blank"
            );
        }

        if (normalized.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException(
                    "content must not exceed "
                            + MAX_CONTENT_LENGTH
                            + " characters"
            );
        }

        return normalized;
    }
}