package com.nexusagent.agent.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.agent.domain.AgentModelConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonAgentModelConfigJsonCodecTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    private final JacksonAgentModelConfigJsonCodec codec =
            new JacksonAgentModelConfigJsonCodec(
                    objectMapper
            );

    @Test
    void shouldRoundTripCompleteModelConfig()
            throws Exception {
        AgentModelConfig config =
                new AgentModelConfig(
                        new BigDecimal("0.2"),
                        new BigDecimal("0.9"),
                        2_048
                );

        String json = codec.encode(config);
        JsonNode root = objectMapper.readTree(json);

        assertAll(
                () -> assertTrue(
                        root.get("temperature")
                                .isNumber()
                ),
                () -> assertEquals(
                        new BigDecimal("0.2"),
                        root.get("temperature")
                                .decimalValue()
                ),
                () -> assertEquals(
                        new BigDecimal("0.9"),
                        root.get("topP")
                                .decimalValue()
                ),
                () -> assertEquals(
                        2_048,
                        root.get("maxOutputTokens")
                                .intValue()
                ),
                () -> assertEquals(
                        config,
                        codec.decode(json)
                )
        );
    }

    @Test
    void shouldPreserveSqlNull() {
        assertAll(
                () -> assertNull(codec.encode(null)),
                () -> assertNull(codec.decode(null))
        );
    }

    @Test
    void shouldEncodeOnlyConfiguredOptions()
            throws Exception {
        AgentModelConfig config =
                new AgentModelConfig(
                        new BigDecimal("0.3"),
                        null,
                        null
                );

        JsonNode root = objectMapper.readTree(
                codec.encode(config)
        );

        assertAll(
                () -> assertEquals(1, root.size()),
                () -> assertTrue(
                        root.has("temperature")
                ),
                () -> assertFalse(
                        root.has("topP")
                ),
                () -> assertFalse(
                        root.has("maxOutputTokens")
                ),
                () -> assertEquals(
                        config,
                        codec.decode(root.toString())
                )
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            """
            {
              "temperature": 0.2,
              "unsupportedOption": true
            }
            """,
            "{not-valid-json}"
    })
    void shouldRejectInvalidOrUnknownJson(
            String json
    ) {
        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> codec.decode(json)
                );

        assertTrue(
                exception.getMessage().contains(
                        "deserialize"
                )
        );
    }
}