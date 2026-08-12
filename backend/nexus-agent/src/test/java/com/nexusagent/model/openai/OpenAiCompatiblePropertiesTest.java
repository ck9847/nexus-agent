package com.nexusagent.model.openai;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatiblePropertiesTest {

    @Test
    void shouldGenerateDefaultChatCompletionsUri() {
        OpenAiCompatibleProperties properties =
                properties(false, "https://api.openai.com/v1", null);

        assertEquals(
                URI.create(
                        "https://api.openai.com/v1/chat/completions"
                ),
                properties.chatCompletionsUri()
        );
    }

    @Test
    void shouldStripTrailingSlashFromCustomBaseUrl() {
        OpenAiCompatibleProperties properties =
                properties(false, "https://api.example.com/api/v1/", null);

        assertEquals(
                URI.create(
                        "https://api.example.com/api/v1/chat/completions"
                ),
                properties.chatCompletionsUri()
        );
    }

    @Test
    void shouldAcceptEnabledWithApiKey() {
        OpenAiCompatibleProperties properties =
                properties(true, "https://api.openai.com/v1", "sk-test-123");

        assertTrue(properties.enabled());
        assertEquals("sk-test-123", properties.apiKey());
    }

    @Test
    void shouldAllowDisabledWithoutApiKey() {
        OpenAiCompatibleProperties properties =
                properties(false, "https://api.openai.com/v1", null);

        assertFalse(properties.enabled());
        assertNull(properties.apiKey());
    }

    @Test
    void shouldRejectEnabledWithoutApiKey() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> properties(
                        true,
                        "https://api.openai.com/v1",
                        null
                )
        );

        assertEquals(
                "OpenAI API key must be configured "
                        + "when the provider is enabled",
                exception.getMessage()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> properties(
                        true,
                        "https://api.openai.com/v1",
                        "   "
                )
        );
    }

    @Test
    void shouldRedactApiKeyInToString() {
        OpenAiCompatibleProperties properties =
                properties(true, "https://api.openai.com/v1", "sk-test-123");

        assertTrue(properties.toString().contains("<redacted>"));
    }

    @Test
    void shouldNotExposeApiKeyInToString() {
        OpenAiCompatibleProperties properties =
                properties(true, "https://api.openai.com/v1", "sk-secret-123456");

        assertFalse(
                properties.toString().contains("sk-secret-123456")
        );
    }

    @Test
    void shouldMarkMissingApiKeyAsNotConfigured() {
        OpenAiCompatibleProperties properties =
                properties(false, "https://api.openai.com/v1", null);

        assertTrue(
                properties.toString().contains("<not-configured>")
        );
    }

    @Test
    void shouldRejectRelativeBaseUrl() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> properties(
                        false,
                        URI.create("/api/v1"),
                        null,
                        Duration.ofSeconds(10),
                        Duration.ofMinutes(2),
                        8192
                )
        );

        assertEquals(
                "OpenAI base URL must be absolute",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectFtpBaseUrl() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> properties(
                        false,
                        URI.create("ftp://api.example.com/v1"),
                        null,
                        Duration.ofSeconds(10),
                        Duration.ofMinutes(2),
                        8192
                )
        );

        assertEquals(
                "OpenAI base URL must use HTTP or HTTPS",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectBaseUrlWithCredentials() {
        assertThrows(
                IllegalArgumentException.class,
                () -> properties(
                        false,
                        URI.create(
                                "https://user:pass@api.example.com/v1"
                        ),
                        null,
                        Duration.ofSeconds(10),
                        Duration.ofMinutes(2),
                        8192
                )
        );
    }

    @Test
    void shouldRejectBaseUrlWithQuery() {
        assertThrows(
                IllegalArgumentException.class,
                () -> properties(
                        false,
                        URI.create(
                                "https://api.example.com/v1?api-version=2024"
                        ),
                        null,
                        Duration.ofSeconds(10),
                        Duration.ofMinutes(2),
                        8192
                )
        );
    }

    @Test
    void shouldRejectBaseUrlWithFragment() {
        assertThrows(
                IllegalArgumentException.class,
                () -> properties(
                        false,
                        URI.create(
                                "https://api.example.com/v1#frag"
                        ),
                        null,
                        Duration.ofSeconds(10),
                        Duration.ofMinutes(2),
                        8192
                )
        );
    }

    @Test
    void shouldRejectZeroConnectTimeout() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> properties(
                        false,
                        "https://api.example.com/v1",
                        null,
                        Duration.ZERO,
                        Duration.ofMinutes(2),
                        8192
                )
        );

        assertEquals(
                "connectTimeout must be positive",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectNegativeReadTimeout() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> properties(
                        false,
                        "https://api.example.com/v1",
                        null,
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(-1),
                        8192
                )
        );

        assertEquals(
                "readTimeout must be positive",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectNullConnectTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> properties(
                        false,
                        "https://api.example.com/v1",
                        null,
                        null,
                        Duration.ofMinutes(2),
                        8192
                )
        );
    }

    @Test
    void shouldAcceptMaxErrorBodyBytesBoundaries() {
        OpenAiCompatibleProperties min =
                properties(false, "https://api.example.com/v1", null,
                        Duration.ofSeconds(10),
                        Duration.ofMinutes(2),
                        1024);

        OpenAiCompatibleProperties max =
                properties(false, "https://api.example.com/v1", null,
                        Duration.ofSeconds(10),
                        Duration.ofMinutes(2),
                        65536);

        assertEquals(1024, min.maxErrorBodyBytes());
        assertEquals(65536, max.maxErrorBodyBytes());
    }

    @Test
    void shouldRejectMaxErrorBodyBytesOutOfRange() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> properties(
                        false,
                        "https://api.example.com/v1",
                        null,
                        Duration.ofSeconds(10),
                        Duration.ofMinutes(2),
                        1023
                )
        );

        assertEquals(
                "maxErrorBodyBytes must be between 1024 and 65536",
                exception.getMessage()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> properties(
                        false,
                        "https://api.example.com/v1",
                        null,
                        Duration.ofSeconds(10),
                        Duration.ofMinutes(2),
                        65537
                )
        );
    }

    private static OpenAiCompatibleProperties properties(
            boolean enabled,
            String baseUrl,
            String apiKey
    ) {
        return properties(
                enabled,
                URI.create(baseUrl),
                apiKey,
                Duration.ofSeconds(10),
                Duration.ofMinutes(2),
                8192
        );
    }

    private static OpenAiCompatibleProperties properties(
            boolean enabled,
            String baseUrl,
            String apiKey,
            Duration connectTimeout,
            Duration readTimeout,
            int maxErrorBodyBytes
    ) {
        return properties(
                enabled,
                URI.create(baseUrl),
                apiKey,
                connectTimeout,
                readTimeout,
                maxErrorBodyBytes
        );
    }

    private static OpenAiCompatibleProperties properties(
            boolean enabled,
            URI baseUrl,
            String apiKey,
            Duration connectTimeout,
            Duration readTimeout,
            int maxErrorBodyBytes
    ) {
        return new OpenAiCompatibleProperties(
                enabled,
                baseUrl,
                apiKey,
                connectTimeout,
                readTimeout,
                maxErrorBodyBytes
        );
    }
}
