package com.nexusagent.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsScrapePropertiesTest {

    private static final String USERNAME = "prometheus";

    private static final String LONG_ENOUGH_PASSWORD =
            "p".repeat(MetricsScrapeProperties
                    .MIN_PASSWORD_LENGTH);

    @Test
    void shouldAcceptEnabledWithLongEnoughPassword() {
        MetricsScrapeProperties properties =
                new MetricsScrapeProperties(
                        true,
                        USERNAME,
                        LONG_ENOUGH_PASSWORD
                );

        assertTrue(properties.enabled());
        assertEquals(USERNAME, properties.username());
        assertEquals(
                LONG_ENOUGH_PASSWORD,
                properties.password()
        );
    }

    @Test
    void shouldAcceptDisabledWithEmptyPassword() {
        MetricsScrapeProperties properties =
                new MetricsScrapeProperties(
                        false,
                        USERNAME,
                        ""
                );

        assertFalse(properties.enabled());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void shouldRejectNullAndShortPasswordWhenEnabled(
            String password
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MetricsScrapeProperties(
                        true,
                        USERNAME,
                        password
                )
        );
    }

    @Test
    void shouldReject31CharacterPasswordWhenEnabled() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MetricsScrapeProperties(
                        true,
                        USERNAME,
                        "p".repeat(
                                MetricsScrapeProperties
                                        .MIN_PASSWORD_LENGTH - 1
                        )
                )
        );
    }

    @Test
    void shouldAccept32CharacterPasswordWhenEnabled() {
        assertDoesNotThrow(() ->
                new MetricsScrapeProperties(
                        true,
                        USERNAME,
                        LONG_ENOUGH_PASSWORD
                )
        );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void shouldRejectBlankUsername(String username) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MetricsScrapeProperties(
                        false,
                        username,
                        LONG_ENOUGH_PASSWORD
                )
        );
    }

    @Test
    void shouldRejectCustomUsernameWhenEnabled() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new MetricsScrapeProperties(
                        true,
                        "custom-scraper",
                        LONG_ENOUGH_PASSWORD
                )
        );

        assertEquals(
                "Metrics scrape username must be prometheus "
                        + "when metrics scrape security is enabled",
                exception.getMessage()
        );
        assertFalse(
                exception.getMessage().contains(
                        LONG_ENOUGH_PASSWORD
                )
        );
    }

    @Test
    void shouldRedactPasswordInToString() {
        String password = "top-secret-password-0123456789abcdef";

        MetricsScrapeProperties properties =
                new MetricsScrapeProperties(
                        true,
                        USERNAME,
                        password
                );

        String rendered = properties.toString();

        assertFalse(
                rendered.contains(password),
                "toString must never expose the password"
        );
        assertFalse(
                rendered.contains("top-secret")
        );
        assertTrue(rendered.contains("[REDACTED]"));
    }
}
