package com.nexusagent.ticket.internal;

import com.nexusagent.ticket.api.InvalidTicketQueryException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Base64TicketCursorCodecTest {

    private final Base64TicketCursorCodec codec =
            new Base64TicketCursorCodec();

    @Test
    void shouldRoundTripCursor() {
        TicketPageCursor original =
                new TicketPageCursor(
                        Instant.parse(
                                "2026-08-08T03:00:00.123Z"
                        ),
                        901L
                );

        String encoded = codec.encode(original);
        TicketPageCursor decoded =
                codec.decode(encoded);

        assertEquals(original, decoded);
        assertFalse(encoded.contains("+"));
        assertFalse(encoded.contains("/"));
        assertFalse(encoded.contains("="));
    }

    @Test
    void shouldRejectMalformedBase64Cursor() {
        assertThrows(
                InvalidTicketQueryException.class,
                () -> codec.decode("%%%")
        );
    }

    @Test
    void shouldRejectMalformedPayload() {
        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        "not-a-valid-payload".getBytes(
                                StandardCharsets.UTF_8
                        )
                );

        assertThrows(
                InvalidTicketQueryException.class,
                () -> codec.decode(encoded)
        );
    }

    @Test
    void shouldRejectNonPositiveTicketId() {
        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        "1786158000123:0".getBytes(
                                StandardCharsets.UTF_8
                        )
                );

        assertThrows(
                InvalidTicketQueryException.class,
                () -> codec.decode(encoded)
        );
    }
}