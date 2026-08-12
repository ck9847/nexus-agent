package com.nexusagent.conversation.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonConversationTurnMetadataJsonCodecTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    private final JacksonConversationTurnMetadataJsonCodec
            codec =
            new JacksonConversationTurnMetadataJsonCodec(
                    objectMapper
            );

    @Test
    void shouldEncodeSimpleMap() throws Exception {
        String json = codec.encode(Map.of(
                "provider",
                "OPENAI",
                "finishReason",
                "STOP",
                "promptTokens",
                12
        ));

        JsonNode node = objectMapper.readTree(json);

        assertEquals(
                "OPENAI",
                node.get("provider").asText()
        );
        assertEquals(
                "STOP",
                node.get("finishReason").asText()
        );
        assertEquals(
                12,
                node.get("promptTokens").asInt()
        );
    }

    @Test
    void shouldEncodeEmptyMap() throws Exception {
        String json = codec.encode(Map.of());

        JsonNode node = objectMapper.readTree(json);

        assertTrue(node.isEmpty());
    }

    @Test
    void shouldEncodeBooleanAndNullValues() throws Exception {
        Map<String, Object> metadata =
                new LinkedHashMap<>();

        metadata.put("retryable", true);
        metadata.put("providerStatus", 429);
        metadata.put("note", null);

        String json = codec.encode(metadata);

        JsonNode node = objectMapper.readTree(json);

        assertTrue(node.get("retryable").asBoolean());
        assertEquals(429, node.get("providerStatus").asInt());
        assertTrue(node.get("note").isNull());
    }

    @Test
    void shouldRejectNullMetadata() {
        NullPointerException exception =
                assertThrows(
                        NullPointerException.class,
                        () -> codec.encode(null)
                );

        assertEquals(
                "metadata must not be null",
                exception.getMessage()
        );
    }

    @Test
    void shouldWrapSerializationFailure() {
        Map<String, Object> cyclic =
                new LinkedHashMap<>();

        cyclic.put("self", cyclic);

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> codec.encode(cyclic)
                );

        assertEquals(
                "Failed to serialize "
                        + "conversation turn metadata",
                exception.getMessage()
        );
    }
}
