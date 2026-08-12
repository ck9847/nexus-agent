package com.nexusagent.model.openai;

import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleErrorMapperTest {

    private final OpenAiCompatibleErrorMapper errorMapper =
            new OpenAiCompatibleErrorMapper();

    @Test
    void shouldMap401ToAuthentication() {
        ChatModelException exception =
                errorMapper.fromHttpStatus(401);

        assertEquals(
                ChatModelErrorCategory.AUTHENTICATION,
                exception.category()
        );
        assertEquals(401, exception.httpStatus());
        assertFalse(exception.retryable());
    }

    @Test
    void shouldMap403ToAuthentication() {
        assertEquals(
                ChatModelErrorCategory.AUTHENTICATION,
                errorMapper.fromHttpStatus(403).category()
        );
    }

    @Test
    void shouldMap400ToInvalidRequest() {
        assertEquals(
                ChatModelErrorCategory.INVALID_REQUEST,
                errorMapper.fromHttpStatus(400).category()
        );
    }

    @Test
    void shouldMap404ToInvalidRequest() {
        assertEquals(
                ChatModelErrorCategory.INVALID_REQUEST,
                errorMapper.fromHttpStatus(404).category()
        );
    }

    @Test
    void shouldMap422ToInvalidRequest() {
        assertEquals(
                ChatModelErrorCategory.INVALID_REQUEST,
                errorMapper.fromHttpStatus(422).category()
        );
    }

    @Test
    void shouldMap408ToTimeout() {
        ChatModelException exception =
                errorMapper.fromHttpStatus(408);

        assertEquals(
                ChatModelErrorCategory.TIMEOUT,
                exception.category()
        );
        assertTrue(exception.retryable());
    }

    @Test
    void shouldMap429ToRateLimit() {
        ChatModelException exception =
                errorMapper.fromHttpStatus(429);

        assertEquals(
                ChatModelErrorCategory.RATE_LIMIT,
                exception.category()
        );
        assertTrue(exception.retryable());
    }

    @Test
    void shouldMap500ToProviderUnavailable() {
        ChatModelException exception =
                errorMapper.fromHttpStatus(500);

        assertEquals(
                ChatModelErrorCategory.PROVIDER_UNAVAILABLE,
                exception.category()
        );
        assertTrue(exception.retryable());
    }

    @Test
    void shouldMap502ToProviderUnavailable() {
        assertEquals(
                ChatModelErrorCategory.PROVIDER_UNAVAILABLE,
                errorMapper.fromHttpStatus(502).category()
        );
    }

    @Test
    void shouldMap503ToProviderUnavailable() {
        assertEquals(
                ChatModelErrorCategory.PROVIDER_UNAVAILABLE,
                errorMapper.fromHttpStatus(503).category()
        );
    }

    @Test
    void shouldMap504ToProviderUnavailable() {
        assertEquals(
                ChatModelErrorCategory.PROVIDER_UNAVAILABLE,
                errorMapper.fromHttpStatus(504).category()
        );
    }

    @Test
    void shouldMapHttpTimeoutCauseToTimeout() {
        RestClientException exception =
                new ResourceAccessException(
                        "read timed out",
                        new HttpTimeoutException("timed out")
                );

        ChatModelException mapped =
                errorMapper.fromTransport(exception);

        assertEquals(
                ChatModelErrorCategory.TIMEOUT,
                mapped.category()
        );
        assertTrue(mapped.retryable());
    }

    @Test
    void shouldMapSocketTimeoutCauseToTimeout() {
        RestClientException exception =
                new ResourceAccessException(
                        "read timed out",
                        new java.net.SocketTimeoutException(
                                "timed out"
                        )
                );

        assertEquals(
                ChatModelErrorCategory.TIMEOUT,
                errorMapper.fromTransport(exception).category()
        );
    }

    @Test
    void shouldMapGenericNetworkExceptionToConnection() {
        RestClientException exception =
                new ResourceAccessException(
                        "Connection refused",
                        new IOException("Connection refused")
                );

        ChatModelException mapped =
                errorMapper.fromTransport(exception);

        assertEquals(
                ChatModelErrorCategory.CONNECTION,
                mapped.category()
        );
        assertTrue(mapped.retryable());
        assertNull(mapped.httpStatus());
    }

    @Test
    void shouldNotExposeResponseBodyOrApiKeyInException() {
        RestClientException raw =
                new ResourceAccessException(
                        "sk-secret-123 response body was invalid",
                        new IOException("boom")
                );

        ChatModelException mapped =
                errorMapper.fromTransport(raw);

        assertFalse(
                mapped.getMessage().contains("sk-secret-123")
        );
        assertFalse(
                mapped.getMessage().contains("response body")
        );
        assertEquals(
                "Could not connect to chat model provider",
                mapped.getMessage()
        );

        ChatModelException http =
                errorMapper.fromHttpStatus(401);

        assertEquals(
                "Chat model provider authentication failed",
                http.getMessage()
        );
        assertFalse(http.getMessage().contains("invalid"));
    }
}
