package com.nexusagent.tool.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisterToolExecutionCommandTest {

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper();

    private static JsonNode input() {
        return OBJECT_MAPPER.createObjectNode()
                .put("temperature", 0.7);
    }

    private static RegisterToolExecutionCommand command(
            JsonNode input
    ) {
        return new RegisterToolExecutionCommand(
                901L,
                500L,
                1001L,
                "call-1",
                "search",
                input,
                true,
                "trace-1"
        );
    }

    @Test
    void shouldAcceptValidCommand() {
        RegisterToolExecutionCommand result =
                command(input());

        assertEquals(901L, result.conversationId());
        assertEquals(500L, result.agentId());
        assertEquals(1001L, result.requestMessageId());
        assertEquals("call-1", result.toolCallId());
        assertEquals("search", result.toolName());
        assertTrue(
                result.input()
                        .get("temperature")
                        .isDouble()
        );
        assertTrue(result.approvalRequired());
        assertEquals("trace-1", result.traceId());
    }

    @Test
    void shouldNormalizeToolCallIdAndToolName() {
        RegisterToolExecutionCommand result =
                new RegisterToolExecutionCommand(
                        901L,
                        500L,
                        1001L,
                        "  call-1  ",
                        "  search  ",
                        input(),
                        false,
                        null
                );

        assertEquals("call-1", result.toolCallId());
        assertEquals("search", result.toolName());
    }

    @Test
    void shouldAllowAbsentTraceId() {
        RegisterToolExecutionCommand result =
                new RegisterToolExecutionCommand(
                        901L,
                        500L,
                        1001L,
                        "call-1",
                        "search",
                        input(),
                        false,
                        null
                );

        assertNull(result.traceId());
    }

    @Test
    void shouldNormalizeTraceId() {
        RegisterToolExecutionCommand trimmed =
                new RegisterToolExecutionCommand(
                        901L,
                        500L,
                        1001L,
                        "call-1",
                        "search",
                        input(),
                        false,
                        "  trace-1  "
                );

        assertEquals("trace-1", trimmed.traceId());
    }

    @Test
    void shouldTreatBlankTraceIdAsAbsent() {
        RegisterToolExecutionCommand blanked =
                new RegisterToolExecutionCommand(
                        901L,
                        500L,
                        1001L,
                        "call-1",
                        "search",
                        input(),
                        false,
                        "   "
                );

        assertNull(blanked.traceId());
    }

    @Test
    void shouldRejectOversizedTraceId() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new RegisterToolExecutionCommand(
                                901L,
                                500L,
                                1001L,
                                "call-1",
                                "search",
                                input(),
                                false,
                                "a".repeat(65)
                        )
                );

        assertEquals(
                "traceId must not exceed 64 characters",
                exception.getMessage()
        );
    }

    @ParameterizedTest
    @MethodSource("nonPositiveIds")
    void shouldRejectNonPositiveIds(
            long conversationId,
            long agentId,
            long requestMessageId
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RegisterToolExecutionCommand(
                        conversationId,
                        agentId,
                        requestMessageId,
                        "call-1",
                        "search",
                        input(),
                        false,
                        null
                )
        );
    }

    @ParameterizedTest
    @MethodSource("invalidToolCallIds")
    void shouldRejectInvalidToolCallIds(
            String toolCallId
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RegisterToolExecutionCommand(
                        901L,
                        500L,
                        1001L,
                        toolCallId,
                        "search",
                        input(),
                        false,
                        null
                )
        );
    }

    @ParameterizedTest
    @MethodSource("invalidToolNames")
    void shouldRejectInvalidToolNames(String toolName) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RegisterToolExecutionCommand(
                        901L,
                        500L,
                        1001L,
                        "call-1",
                        toolName,
                        input(),
                        false,
                        null
                )
        );
    }

    @Test
    void shouldRejectNullInput() {
        NullPointerException exception =
                assertThrows(
                        NullPointerException.class,
                        () -> new RegisterToolExecutionCommand(
                                901L,
                                500L,
                                1001L,
                                "call-1",
                                "search",
                                null,
                                false,
                                null
                        )
                );

        assertEquals(
                "input must not be null",
                exception.getMessage()
        );
    }

    @ParameterizedTest
    @MethodSource("nonObjectInputs")
    void shouldRejectNonObjectInputs(JsonNode input) {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new RegisterToolExecutionCommand(
                                901L,
                                500L,
                                1001L,
                                "call-1",
                                "search",
                                input,
                                false,
                                null
                        )
                );

        assertEquals(
                "input must be a JSON object",
                exception.getMessage()
        );
    }

    @Test
    void shouldDefensivelyCopyInputOnConstruction() {
        ObjectNode input = OBJECT_MAPPER.createObjectNode();
        input.put("temperature", 0.7);

        RegisterToolExecutionCommand result =
                command(input);

        input.put("temperature", 99);

        assertEquals(
                0.7,
                result.input()
                        .get("temperature")
                        .asDouble()
        );
    }

    @Test
    void shouldDefensivelyCopyInputOnAccess() {
        RegisterToolExecutionCommand result =
                command(input());

        ((ObjectNode) result.input())
                .put("temperature", 99);

        assertEquals(
                0.7,
                result.input()
                        .get("temperature")
                        .asDouble()
        );
    }

    private static Stream<Arguments> nonPositiveIds() {
        return Stream.of(
                Arguments.of(0L, 500L, 1001L),
                Arguments.of(-901L, 500L, 1001L),
                Arguments.of(901L, 0L, 1001L),
                Arguments.of(901L, -500L, 1001L),
                Arguments.of(901L, 500L, 0L),
                Arguments.of(901L, 500L, -1001L)
        );
    }

    private static Stream<Arguments> invalidToolCallIds() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of(""),
                Arguments.of(" "),
                Arguments.of("a".repeat(129))
        );
    }

    private static Stream<Arguments> invalidToolNames() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of(""),
                Arguments.of(" "),
                Arguments.of("a".repeat(65)),
                Arguments.of("Search"),
                Arguments.of("search tool"),
                Arguments.of("1search"),
                Arguments.of("search-tool")
        );
    }

    private static Stream<Arguments> nonObjectInputs() {
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
