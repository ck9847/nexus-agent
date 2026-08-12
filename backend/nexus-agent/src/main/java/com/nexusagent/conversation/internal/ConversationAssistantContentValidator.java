package com.nexusagent.conversation.internal;

final class ConversationAssistantContentValidator {

    private static final int MAX_CONTENT_LENGTH = 50_000;

    private ConversationAssistantContentValidator() {
    }

    static String requireValid(String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "assistant content must not be null"
            );
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "assistant content must not be blank"
            );
        }

        if (value.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException(
                    "assistant content must not exceed "
                            + MAX_CONTENT_LENGTH
                            + " characters"
            );
        }

        return value;
    }
}