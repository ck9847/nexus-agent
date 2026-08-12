package com.nexusagent.conversation.internal;

final class ConversationIdParser {

    private ConversationIdParser() {
    }

    static long parse(String value) {
        if (value == null) {
            throw invalidConversationId();
        }

        String normalized = value.trim();

        if (normalized.isEmpty()
                || normalized.chars().anyMatch(
                character -> character < '0'
                        || character > '9'
        )) {
            throw invalidConversationId();
        }

        long parsed;

        try {
            parsed = Long.parseLong(normalized);
        } catch (NumberFormatException exception) {
            throw invalidConversationId();
        }

        if (parsed <= 0) {
            throw invalidConversationId();
        }

        return parsed;
    }

    private static IllegalArgumentException
    invalidConversationId() {
        return new IllegalArgumentException(
                "conversationId must be "
                        + "a positive integer"
        );
    }
}