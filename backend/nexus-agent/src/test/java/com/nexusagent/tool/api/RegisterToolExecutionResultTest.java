package com.nexusagent.tool.api;

import com.nexusagent.tool.domain.ToolExecutionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisterToolExecutionResultTest {

    private static final String VALID_KEY =
            "tool:v1:" + "a".repeat(64);

    private static RegisterToolExecutionResult result(
            long toolExecutionId,
            String idempotencyKey,
            ToolExecutionStatus status,
            Instant createdAt
    ) {
        return new RegisterToolExecutionResult(
                toolExecutionId,
                idempotencyKey,
                status,
                true,
                createdAt
        );
    }

    @Test
    void shouldAcceptValidResult() {
        Instant createdAt = Instant.now();

        RegisterToolExecutionResult value = result(
                1001L,
                VALID_KEY,
                ToolExecutionStatus.PENDING,
                createdAt
        );

        assertEquals(1001L, value.toolExecutionId());
        assertEquals(VALID_KEY, value.idempotencyKey());
        assertEquals(
                ToolExecutionStatus.PENDING,
                value.status()
        );
        assertTrue(value.newlyCreated());
        assertEquals(createdAt, value.createdAt());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void shouldRejectNonPositiveIds(long toolExecutionId) {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> result(
                                toolExecutionId,
                                VALID_KEY,
                                ToolExecutionStatus.PENDING,
                                Instant.now()
                        )
                );

        assertEquals(
                "toolExecutionId must be positive",
                exception.getMessage()
        );
    }

    @ParameterizedTest
    @MethodSource("invalidKeys")
    void shouldRejectInvalidIdempotencyKeys(
            String idempotencyKey
    ) {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> result(
                                1001L,
                                idempotencyKey,
                                ToolExecutionStatus.PENDING,
                                Instant.now()
                        )
                );

        assertEquals(
                "idempotencyKey has invalid format",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectNullStatus() {
        NullPointerException exception =
                assertThrows(
                        NullPointerException.class,
                        () -> result(
                                1001L,
                                VALID_KEY,
                                null,
                                Instant.now()
                        )
                );

        assertEquals(
                "status must not be null",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectNullCreatedAt() {
        NullPointerException exception =
                assertThrows(
                        NullPointerException.class,
                        () -> result(
                                1001L,
                                VALID_KEY,
                                ToolExecutionStatus.PENDING,
                                null
                        )
                );

        assertEquals(
                "createdAt must not be null",
                exception.getMessage()
        );
    }

    private static Stream<Arguments> invalidKeys() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of(""),
                Arguments.of(
                        "tool:v1:" + "a".repeat(63)
                ),
                Arguments.of(
                        "tool:v1:" + "a".repeat(65)
                ),
                Arguments.of(
                        "tool:v1:" + "g".repeat(64)
                ),
                Arguments.of(
                        "tool:v2:" + "a".repeat(64)
                ),
                Arguments.of(
                        "Tool:v1:" + "a".repeat(64)
                ),
                Arguments.of(
                        "tool:v1:" + "A".repeat(64)
                )
        );
    }
}
