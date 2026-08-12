package com.nexusagent.model.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatModelOptionsTest {

    @Test
    void shouldAcceptAllNullFields() {
        ChatModelOptions options =
                new ChatModelOptions(null, null, null);

        assertNull(options.temperature());
        assertNull(options.topP());
        assertNull(options.maxOutputTokens());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 2.0})
    void shouldAcceptTemperatureBoundaries(double temperature) {
        ChatModelOptions options =
                new ChatModelOptions(
                        BigDecimal.valueOf(temperature),
                        null,
                        null
                );

        assertEquals(
                BigDecimal.valueOf(temperature),
                options.temperature()
        );
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 2.1})
    void shouldRejectOutOfRangeTemperature(double temperature) {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new ChatModelOptions(
                                BigDecimal.valueOf(temperature),
                                null,
                                null
                        )
                );

        assertEquals(
                "temperature must be between 0.0 and 2.0",
                exception.getMessage()
        );
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 1.0})
    void shouldAcceptTopPBoundaries(double topP) {
        ChatModelOptions options =
                new ChatModelOptions(
                        null,
                        BigDecimal.valueOf(topP),
                        null
                );

        assertEquals(
                BigDecimal.valueOf(topP),
                options.topP()
        );
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1})
    void shouldRejectOutOfRangeTopP(double topP) {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new ChatModelOptions(
                                null,
                                BigDecimal.valueOf(topP),
                                null
                        )
                );

        assertEquals(
                "topP must be between 0.0 and 1.0",
                exception.getMessage()
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 131072})
    void shouldAcceptMaxOutputTokensBoundaries(int maxOutputTokens) {
        ChatModelOptions options =
                new ChatModelOptions(
                        null,
                        null,
                        maxOutputTokens
                );

        assertEquals(
                maxOutputTokens,
                options.maxOutputTokens()
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 131073})
    void shouldRejectOutOfRangeMaxOutputTokens(int maxOutputTokens) {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new ChatModelOptions(
                                null,
                                null,
                                maxOutputTokens
                        )
                );

        assertEquals(
                "maxOutputTokens must be between 1 and 131072",
                exception.getMessage()
        );
    }

    @Test
    void shouldReturnDefaultsWithAllFieldsNull() {
        ChatModelOptions defaults =
                ChatModelOptions.defaults();

        assertNull(defaults.temperature());
        assertNull(defaults.topP());
        assertNull(defaults.maxOutputTokens());
    }
}
