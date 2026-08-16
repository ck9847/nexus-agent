package com.nexusagent.tool.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutionIdempotencyKeyFactoryTest {

    private static final Pattern KEY_PATTERN =
            Pattern.compile("tool:v1:[0-9a-f]{64}");

    private static final ToolExecutionIdempotencyKeyFactory FACTORY =
            new ToolExecutionIdempotencyKeyFactory();

    private static String key(
            long tenantId,
            long conversationId,
            long agentId,
            long requestMessageId,
            String toolCallId,
            String toolName
    ) {
        return FACTORY.create(
                tenantId,
                conversationId,
                agentId,
                requestMessageId,
                toolCallId,
                toolName
        );
    }

    @Test
    void shouldProduceSameKeyForSameInput() {
        String first = key(1L, 2L, 3L, 4L, "call-1", "search");
        String second = key(1L, 2L, 3L, 4L, "call-1", "search");

        assertEquals(first, second);
    }

    @Test
    void shouldProduceKeysMatchingExpectedFormat() {
        String generated = key(
                1L,
                2L,
                3L,
                4L,
                "call-1",
                "search"
        );

        assertTrue(
                KEY_PATTERN.matcher(generated).matches()
        );
    }

    @ParameterizedTest
    @MethodSource("mutatedInputs")
    void shouldChangeKeyWhenAnyFieldChanges(
            long tenantId,
            long conversationId,
            long agentId,
            long requestMessageId,
            String toolCallId,
            String toolName
    ) {
        String base = key(1L, 2L, 3L, 4L, "call-1", "search");

        assertNotEquals(
                base,
                key(
                        tenantId,
                        conversationId,
                        agentId,
                        requestMessageId,
                        toolCallId,
                        toolName
                )
        );
    }

    @Test
    void shouldNormalizeSurroundingWhitespace() {
        String base = key(1L, 2L, 3L, 4L, "call-1", "search");

        assertEquals(
                base,
                key(
                        1L,
                        2L,
                        3L,
                        4L,
                        "  call-1  ",
                        "  search  "
                )
        );
    }

    @Test
    void shouldDistinguishBoundaryInputsWithLengthPrefixes() {
        assertNotEquals(
                key(1L, 2L, 3L, 4L, "ab", "c"),
                key(1L, 2L, 3L, 4L, "a", "bc")
        );
    }

    @ParameterizedTest
    @MethodSource("nonPositiveIds")
    void shouldRejectNonPositiveIds(
            long tenantId,
            long conversationId,
            long agentId,
            long requestMessageId
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> key(
                        tenantId,
                        conversationId,
                        agentId,
                        requestMessageId,
                        "call-1",
                        "search"
                )
        );
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "   "})
    void shouldRejectNullOrBlankToolCallId(
            String toolCallId
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> key(
                        1L,
                        2L,
                        3L,
                        4L,
                        toolCallId,
                        "search"
                )
        );
    }

    @Test
    void shouldRejectOversizedToolCallId() {
        String toolCallId = "a".repeat(129);

        assertThrows(
                IllegalArgumentException.class,
                () -> key(
                        1L,
                        2L,
                        3L,
                        4L,
                        toolCallId,
                        "search"
                )
        );
    }

    @ParameterizedTest
    @MethodSource("invalidToolNames")
    void shouldRejectInvalidToolNames(String toolName) {
        assertThrows(
                IllegalArgumentException.class,
                () -> key(
                        1L,
                        2L,
                        3L,
                        4L,
                        "call-1",
                        toolName
                )
        );
    }

    private static Stream<Arguments> mutatedInputs() {
        return Stream.of(
                Arguments.of(
                        2L,
                        2L,
                        3L,
                        4L,
                        "call-1",
                        "search"
                ),
                Arguments.of(
                        1L,
                        3L,
                        3L,
                        4L,
                        "call-1",
                        "search"
                ),
                Arguments.of(
                        1L,
                        2L,
                        4L,
                        4L,
                        "call-1",
                        "search"
                ),
                Arguments.of(
                        1L,
                        2L,
                        3L,
                        5L,
                        "call-1",
                        "search"
                ),
                Arguments.of(
                        1L,
                        2L,
                        3L,
                        4L,
                        "call-2",
                        "search"
                ),
                Arguments.of(
                        1L,
                        2L,
                        3L,
                        4L,
                        "call-1",
                        "search_web"
                )
        );
    }

    @Test
    void shouldDeriveStableClientTurnKey() {
        String first = FACTORY.createForClientTurn(
                3L,
                901L,
                "client-key-1"
        );

        String second = FACTORY.createForClientTurn(
                3L,
                901L,
                "client-key-1"
        );

        assertEquals(first, second);
        assertTrue(first.startsWith("tool:turn:v1:"));
        assertTrue(first.length() <= 128);
    }

    @Test
    void shouldScopeClientTurnKeyByTenantAndConversation() {
        String base = FACTORY.createForClientTurn(
                3L,
                901L,
                "client-key-1"
        );

        assertNotEquals(
                base,
                FACTORY.createForClientTurn(
                        4L,
                        901L,
                        "client-key-1"
                ),
                "不同租户的相同客户端键必须不同"
        );

        assertNotEquals(
                base,
                FACTORY.createForClientTurn(
                        3L,
                        902L,
                        "client-key-1"
                ),
                "不同会话的相同客户端键必须不同"
        );
    }

    @Test
    void shouldDistinguishClientTurnKeyFromDerivedCallKey() {
        String clientKey = FACTORY.createForClientTurn(
                3L,
                901L,
                "client-key-1"
        );

        String derivedKey = FACTORY.create(
                3L,
                901L,
                500L,
                1001L,
                "call-1",
                "create_ticket"
        );

        assertNotEquals(clientKey, derivedKey);
        assertTrue(derivedKey.startsWith("tool:v1:"));
    }

    @Test
    void shouldNormalizeAndValidateClientTurnKey() {
        assertEquals(
                FACTORY.createForClientTurn(3L, 901L, " key "),
                FACTORY.createForClientTurn(3L, 901L, "key")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> FACTORY.createForClientTurn(
                        3L,
                        901L,
                        " "
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> FACTORY.createForClientTurn(
                        3L,
                        901L,
                        null
                )
        );
    }

    private static Stream<Arguments> nonPositiveIds() {
        return Stream.of(
                Arguments.of(0L, 2L, 3L, 4L),
                Arguments.of(-1L, 2L, 3L, 4L),
                Arguments.of(1L, 0L, 3L, 4L),
                Arguments.of(1L, -2L, 3L, 4L),
                Arguments.of(1L, 2L, 0L, 4L),
                Arguments.of(1L, 2L, -3L, 4L),
                Arguments.of(1L, 2L, 3L, 0L),
                Arguments.of(1L, 2L, 3L, -4L)
        );
    }

    private static Stream<Arguments> invalidToolNames() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of(""),
                Arguments.of(" "),
                Arguments.of("Search"),
                Arguments.of("SEARCH"),
                Arguments.of("search tool"),
                Arguments.of("1search"),
                Arguments.of("search-tool"),
                Arguments.of("a".repeat(65))
        );
    }
}
