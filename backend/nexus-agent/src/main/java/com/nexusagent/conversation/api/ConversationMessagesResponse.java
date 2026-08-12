package com.nexusagent.conversation.api;

import java.util.List;
import java.util.Objects;

public record ConversationMessagesResponse(
        List<ConversationMessageResponse> items,
        String nextCursor,
        boolean hasMore
) {

    public ConversationMessagesResponse {
        Objects.requireNonNull(
                items,
                "items must not be null"
        );

        items = List.copyOf(items);

        if (hasMore
                && (nextCursor == null
                || nextCursor.isBlank())) {
            throw new IllegalArgumentException(
                    "nextCursor is required "
                            + "when hasMore is true"
            );
        }

        if (!hasMore && nextCursor != null) {
            throw new IllegalArgumentException(
                    "nextCursor must be null "
                            + "when hasMore is false"
            );
        }
    }
}