package com.nexusagent.tool.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JacksonCanonicalToolInputJsonCodecTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    private final JacksonCanonicalToolInputJsonCodec codec =
            new JacksonCanonicalToolInputJsonCodec(
                    objectMapper
            );

    @Test
    void shouldSortTopLevelFields() throws Exception {
        JsonNode node = objectMapper.readTree(
                "{\"z\":1,\"a\":2,\"m\":3}"
        );

        assertEquals(
                "{\"a\":2,\"m\":3,\"z\":1}",
                codec.encode(node)
        );
    }

    @Test
    void shouldSortNestedObjectFields() throws Exception {
        JsonNode node = objectMapper.readTree(
                "{\"b\":{\"d\":4,\"c\":3},\"a\":1}"
        );

        assertEquals(
                "{\"a\":1,\"b\":{\"c\":3,\"d\":4}}",
                codec.encode(node)
        );
    }

    @Test
    void shouldPreserveArrayOrder() throws Exception {
        JsonNode node = objectMapper.readTree(
                "{\"list\":[3,1,2]}"
        );

        JsonNode decoded = codec.decode(
                codec.encode(node)
        );

        assertEquals(
                3,
                decoded.get("list").get(0).asInt()
        );
        assertEquals(
                1,
                decoded.get("list").get(1).asInt()
        );
        assertEquals(
                2,
                decoded.get("list").get(2).asInt()
        );
    }

    @Test
    void shouldEncodeSameContentIdentically() throws Exception {
        JsonNode first = objectMapper.readTree(
                """
                {
                    "zebra": 1,
                    "alpha": {"y": 2, "x": 3},
                    "list": [
                        {"b": 2, "a": 1},
                        {"d": 4, "c": 3}
                    ]
                }
                """
        );
        JsonNode second = objectMapper.readTree(
                """
                {
                    "alpha": {"x": 3, "y": 2},
                    "list": [
                        {"a": 1, "b": 2},
                        {"c": 3, "d": 4}
                    ],
                    "zebra": 1
                }
                """
        );

        assertEquals(
                codec.encode(first),
                codec.encode(second)
        );
    }

    @Test
    void shouldReCanonicalizeOnDecode() {
        JsonNode decoded = codec.decode(
                "{\"b\":1,\"a\":{\"z\":3,\"y\":2}}"
        );

        assertEquals(
                "{\"a\":{\"y\":2,\"z\":3},\"b\":1}",
                decoded.toString()
        );
    }

    @Test
    void shouldCanonicalizeNestedArrayElementsOnDecode() {
        JsonNode decoded = codec.decode(
                """
                {"list":[{"b":2,"a":1}],"top":{"y":2,"x":1}}
                """
        );

        assertEquals(
                "{\"list\":[{\"a\":1,\"b\":2}],"
                        + "\"top\":{\"x\":1,\"y\":2}}",
                decoded.toString()
        );
    }

    @Test
    void shouldRoundTripStably() throws Exception {
        JsonNode node = objectMapper.readTree(
                """
                {
                    "query": "latest",
                    "filters": [
                        {"field": "status", "op": "eq"},
                        {"op": "lt", "field": "price"}
                    ],
                    "limit": 5
                }
                """
        );

        String encoded = codec.encode(node);

        assertEquals(
                encoded,
                codec.encode(codec.decode(encoded))
        );
    }

    @Test
    void shouldAcceptInputAtSizeLimit() throws Exception {
        String value = "x".repeat(65528);
        JsonNode node = objectMapper.readTree(
                "{\"v\":\"" + value + "\"}"
        );

        assertDoesNotThrow(() -> codec.encode(node));
    }

    @Test
    void shouldRejectInputOverSizeLimit() throws Exception {
        String value = "x".repeat(65529);
        JsonNode node = objectMapper.readTree(
                "{\"v\":\"" + value + "\"}"
        );

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> codec.encode(node)
                );

        assertEquals(
                "input must not exceed "
                        + "65536 UTF-8 bytes",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectOversizedJsonOnDecode() {
        String json =
                "{\"v\":\""
                        + "x".repeat(65529)
                        + "\"}";

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> codec.decode(json)
                );

        assertEquals(
                "input must not exceed "
                        + "65536 UTF-8 bytes",
                exception.getMessage()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"a\":1} garbage",
            "{\"a\":1}{\"b\":2}",
            "{\"a\":1} []"
    })
    void shouldRejectTrailingTokensOnDecode(
            String json
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(json)
        );
    }

    @Test
    void shouldRejectNullInputOnEncode() {
        NullPointerException exception =
                assertThrows(
                        NullPointerException.class,
                        () -> codec.encode(null)
                );

        assertEquals(
                "input must not be null",
                exception.getMessage()
        );
    }

    @ParameterizedTest
    @MethodSource("nonObjectNodes")
    void shouldRejectNonObjectInputsOnEncode(
            JsonNode input
    ) {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> codec.encode(input)
                );

        assertEquals(
                "input must be a JSON object",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectNullInputOnDecode() {
        NullPointerException exception =
                assertThrows(
                        NullPointerException.class,
                        () -> codec.decode(null)
                );

        assertEquals(
                "json must not be null",
                exception.getMessage()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "{",
            "not json",
            "{\"a\":1"
    })
    void shouldRejectBlankOrMalformedJsonOnDecode(
            String json
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(json)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "42",
            "\"text\"",
            "true",
            "null"
    })
    void shouldRejectNonObjectJsonOnDecode(String json) {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> codec.decode(json)
                );

        assertEquals(
                "tool input must be a JSON object",
                exception.getMessage()
        );
    }

    private static Stream<Arguments> nonObjectNodes() {
        ObjectMapper mapper = new ObjectMapper();

        return Stream.of(
                Arguments.of(mapper.createArrayNode()),
                Arguments.of(
                        mapper.getNodeFactory()
                                .textNode("hello")
                ),
                Arguments.of(
                        mapper.getNodeFactory()
                                .numberNode(42)
                ),
                Arguments.of(
                        mapper.getNodeFactory()
                                .booleanNode(true)
                ),
                Arguments.of(
                        mapper.getNodeFactory()
                                .nullNode()
                )
        );
    }
}
