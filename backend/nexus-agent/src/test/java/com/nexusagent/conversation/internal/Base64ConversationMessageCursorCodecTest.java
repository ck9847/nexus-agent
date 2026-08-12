package com.nexusagent.conversation.internal;

import com.nexusagent.conversation.api.InvalidConversationQueryException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Base64ConversationMessageCursorCodecTest {

    private static final String INVALID_CURSOR_MESSAGE =
            "Invalid conversation message cursor";

    private static final int MAX_CURSOR_LENGTH = 256;

    private final Base64ConversationMessageCursorCodec codec =
            new Base64ConversationMessageCursorCodec();

    @ParameterizedTest
    @ValueSource(longs = {901L, 1L, 123456789012345678L})
    void shouldRoundTripCursor(long conversationId) {
        ConversationMessageCursor original =
                new ConversationMessageCursor(
                        conversationId,
                        42L
                );

        assertEquals(
                original,
                codec.decode(codec.encode(original))
        );
    }

    @Test
    void shouldEncodeWithoutUrlUnsafeOrPaddingCharacters() {
        String encoded = codec.encode(
                new ConversationMessageCursor(901L, 20L)
        );

        assertFalse(encoded.indexOf('+') >= 0);
        assertFalse(encoded.indexOf('/') >= 0);
        assertFalse(encoded.indexOf('=') >= 0);
    }

    @Test
    void shouldRejectNullCursorOnEncode() {
        assertThrows(
                NullPointerException.class,
                () -> codec.encode(null)
        );
    }

    @Test
    void shouldRejectNullCursorOnDecode() {
        assertInvalidDecode(null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void shouldRejectBlankCursorOnDecode(String cursor) {
        assertInvalidDecode(cursor);
    }

    @Test
    void shouldRejectNonBase64Cursor() {
        assertInvalidDecode("!!!!");
    }

    @Test
    void shouldRejectPaddedCursor() {
        String padded = Base64.getEncoder()
                .encodeToString(
                        "v1:901:2".getBytes(
                                StandardCharsets.UTF_8
                        )
                );

        assertInvalidDecode(padded);
    }

    @Test
    void shouldRejectWrongVersion() {
        assertInvalidDecode(encodePayload("v2:901:2"));
    }

    @Test
    void shouldRejectTooFewFields() {
        assertInvalidDecode(encodePayload("v1:901"));
        assertInvalidDecode(encodePayload("v1"));
    }

    @Test
    void shouldRejectTooManyFields() {
        assertInvalidDecode(
                encodePayload("v1:901:2:extra")
        );
    }

    @Test
    void shouldRejectNonNumericConversationId() {
        assertInvalidDecode(encodePayload("v1:abc:2"));
    }

    @Test
    void shouldRejectZeroConversationId() {
        assertInvalidDecode(encodePayload("v1:0:2"));
    }

    @Test
    void shouldRejectNegativeConversationId() {
        assertInvalidDecode(encodePayload("v1:-1:2"));
    }

    @Test
    void shouldRejectOverflowingConversationId() {
        assertInvalidDecode(
                encodePayload("v1:9223372036854775808:2")
        );
    }

    @Test
    void shouldRejectNonNumericSequenceNo() {
        assertInvalidDecode(encodePayload("v1:901:abc"));
    }

    @Test
    void shouldRejectZeroSequenceNo() {
        assertInvalidDecode(encodePayload("v1:901:0"));
    }

    @Test
    void shouldRejectNegativeSequenceNo() {
        assertInvalidDecode(encodePayload("v1:901:-1"));
    }

    @Test
    void shouldRejectOverflowingSequenceNo() {
        assertInvalidDecode(
                encodePayload("v1:901:9223372036854775808")
        );
    }

    @Test
    void shouldRejectCursorOverMaximumLength() {
        assertInvalidDecode("A".repeat(MAX_CURSOR_LENGTH + 1));
    }

    private static String encodePayload(String payload) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        payload.getBytes(StandardCharsets.UTF_8)
                );
    }

    private void assertInvalidDecode(String cursor) {
        InvalidConversationQueryException exception =
                assertThrows(
                        InvalidConversationQueryException.class,
                        () -> codec.decode(cursor)
                );

        assertEquals(
                INVALID_CURSOR_MESSAGE,
                exception.getMessage()
        );
    }
}
