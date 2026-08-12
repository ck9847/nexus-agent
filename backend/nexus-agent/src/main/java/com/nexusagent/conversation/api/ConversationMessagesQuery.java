package com.nexusagent.conversation.api;

public record ConversationMessagesQuery(
        int limit,
        String cursor
) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private static final int MAX_CURSOR_LENGTH = 256;

    public ConversationMessagesQuery {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidConversationQueryException(
                    "limit must be between 1 and "
                            + MAX_LIMIT
            );
        }

        if (cursor != null) {
            cursor = cursor.trim();

            if (cursor.isBlank()) {
                cursor = null;
            } else if (cursor.length()
                    > MAX_CURSOR_LENGTH) {
                throw new InvalidConversationQueryException(
                        "Invalid conversation message cursor"
                );
            }
        }
    }
}