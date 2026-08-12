package com.nexusagent.model.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatModelExceptionTest {

    @Test
    void shouldMapRateLimitStatusToRetryableCategory() {
        ChatModelException exception =
                new ChatModelException(
                        ChatModelErrorCategory.RATE_LIMIT,
                        "Rate limit exceeded",
                        429,
                        null
                );

        assertEquals(
                ChatModelErrorCategory.RATE_LIMIT,
                exception.category()
        );
        assertEquals(429, exception.httpStatus());
        assertTrue(exception.retryable());
    }

    @Test
    void shouldMapAuthenticationStatusToNonRetryableCategory() {
        ChatModelException exception =
                new ChatModelException(
                        ChatModelErrorCategory.AUTHENTICATION,
                        "Invalid API key",
                        401,
                        null
                );

        assertEquals(
                ChatModelErrorCategory.AUTHENTICATION,
                exception.category()
        );
        assertEquals(401, exception.httpStatus());
        assertFalse(exception.retryable());
    }

    @Test
    void shouldPreserveCauseAndSafeMessage() {
        RuntimeException cause =
                new RuntimeException("underlying failure");

        ChatModelException exception =
                new ChatModelException(
                        ChatModelErrorCategory.TIMEOUT,
                        "Request timed out",
                        504,
                        cause
                );

        assertSame(cause, exception.getCause());
        assertEquals(
                "Request timed out",
                exception.getMessage()
        );
    }

    @Test
    void shouldAllowNullHttpStatusAndCause() {
        ChatModelException exception =
                new ChatModelException(
                        ChatModelErrorCategory.CONNECTION,
                        "Connection refused"
                );

        assertNull(exception.httpStatus());
        assertNull(exception.getCause());
        assertTrue(exception.retryable());
    }

    @ParameterizedTest
    @ValueSource(ints = {99, 600})
    void shouldRejectInvalidHttpStatus(int httpStatus) {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new ChatModelException(
                                ChatModelErrorCategory.INVALID_REQUEST,
                                "Bad request",
                                httpStatus,
                                null
                        )
                );

        assertEquals(
                "httpStatus must be between 100 and 599",
                exception.getMessage()
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 599})
    void shouldAcceptHttpStatusBoundaries(int httpStatus) {
        ChatModelException exception =
                new ChatModelException(
                        ChatModelErrorCategory.INVALID_REQUEST,
                        "Bad request",
                        httpStatus,
                        null
                );

        assertEquals(httpStatus, exception.httpStatus());
    }

    @Test
    void shouldRejectNullSafeMessage() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChatModelException(
                        ChatModelErrorCategory.INVALID_REQUEST,
                        null
                )
        );
    }

    @Test
    void shouldRejectBlankSafeMessage() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChatModelException(
                        ChatModelErrorCategory.INVALID_REQUEST,
                        "   "
                )
        );
    }

    @Test
    void shouldRejectNullCategory() {
        assertThrows(
                NullPointerException.class,
                () -> new ChatModelException(
                        null,
                        "message"
                )
        );
    }
}
