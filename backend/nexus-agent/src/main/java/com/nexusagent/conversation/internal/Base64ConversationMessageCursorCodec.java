package com.nexusagent.conversation.internal;

import com.nexusagent.conversation.api.InvalidConversationQueryException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

@Component
public final class Base64ConversationMessageCursorCodec
        implements ConversationMessageCursorCodec {

    private static final String VERSION = "v1";
    private static final int MAX_CURSOR_LENGTH = 256;

    @Override
    public String encode(
            ConversationMessageCursor cursor
    ) {
        Objects.requireNonNull(
                cursor,
                "cursor must not be null"
        );

        String payload =
                VERSION
                        + ":"
                        + cursor.conversationId()
                        + ":"
                        + cursor.sequenceNo();

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        payload.getBytes(
                                StandardCharsets.UTF_8
                        )
                );
    }

    @Override
    public ConversationMessageCursor decode(
            String cursor
    ) {
        if (cursor == null
                || cursor.isBlank()
                || cursor.length()
                > MAX_CURSOR_LENGTH
                || cursor.indexOf('=') >= 0
                || cursor.indexOf('+') >= 0
                || cursor.indexOf('/') >= 0) {
            throw invalidCursor();
        }

        try {
            byte[] decoded =
                    Base64.getUrlDecoder().decode(
                            cursor
                    );

            String payload = new String(
                    decoded,
                    StandardCharsets.UTF_8
            );

            String[] parts = payload.split(
                    ":",
                    -1
            );

            if (parts.length != 3
                    || !VERSION.equals(parts[0])) {
                throw new IllegalArgumentException(
                        "Invalid cursor payload"
                );
            }

            long conversationId =
                    Long.parseLong(parts[1]);

            long sequenceNo =
                    Long.parseLong(parts[2]);

            return new ConversationMessageCursor(
                    conversationId,
                    sequenceNo
            );
        } catch (IllegalArgumentException exception) {
            throw invalidCursor();
        }
    }

    private static InvalidConversationQueryException
    invalidCursor() {
        return new InvalidConversationQueryException(
                "Invalid conversation message cursor"
        );
    }
}