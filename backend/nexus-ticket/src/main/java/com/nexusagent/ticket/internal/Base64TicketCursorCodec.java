package com.nexusagent.ticket.internal;

import com.nexusagent.ticket.api.InvalidTicketQueryException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

@Component
public final class Base64TicketCursorCodec
        implements TicketCursorCodec {

    @Override
    public String encode(TicketPageCursor cursor) {
        Objects.requireNonNull(
                cursor,
                "cursor must not be null"
        );

        String payload =
                cursor.createdAt().toEpochMilli()
                        + ":"
                        + cursor.ticketId();

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        payload.getBytes(
                                StandardCharsets.UTF_8
                        )
                );
    }

    @Override
    public TicketPageCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
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

            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "Invalid cursor payload"
                );
            }

            long epochMilli =
                    Long.parseLong(parts[0]);
            long ticketId =
                    Long.parseLong(parts[1]);

            return new TicketPageCursor(
                    Instant.ofEpochMilli(epochMilli),
                    ticketId
            );
        } catch (
                IllegalArgumentException
                | DateTimeException exception
        ) {
            throw invalidCursor();
        }
    }

    private static InvalidTicketQueryException
    invalidCursor() {
        return new InvalidTicketQueryException(
                "Invalid ticket query cursor"
        );
    }
}