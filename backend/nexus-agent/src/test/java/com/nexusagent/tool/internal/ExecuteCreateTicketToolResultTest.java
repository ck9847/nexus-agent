package com.nexusagent.tool.internal;

import com.nexusagent.ticket.domain.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecuteCreateTicketToolResultTest {

    private static final Instant PREPARED_AT =
            Instant.parse("2026-08-13T10:15:32.123Z");

    @Test
    void shouldAcceptValidResult() {
        ExecuteCreateTicketToolResult result =
                new ExecuteCreateTicketToolResult(
                        7001L,
                        "9001",
                        "TKT-A1",
                        TicketStatus.OPEN,
                        8001L,
                        3L,
                        8002L,
                        4L,
                        2,
                        PREPARED_AT,
                        true
                );

        assertEquals(7001L, result.toolExecutionId());
        assertEquals("9001", result.ticketId());
        assertEquals("TKT-A1", result.ticketNo());
        assertEquals(TicketStatus.OPEN, result.ticketStatus());
        assertEquals(8001L, result.resultMessageId());
        assertEquals(3L, result.resultMessageSequenceNo());
        assertEquals(8002L, result.assistantMessageId());
        assertEquals(4L, result.assistantSequenceNo());
        assertEquals(2, result.conversationVersion());
        assertEquals(PREPARED_AT, result.assistantPreparedAt());
        assertTrue(result.replayed());
    }

    @ParameterizedTest
    @MethodSource("nonPositiveIds")
    void shouldRejectNonPositiveIds(
            long toolExecutionId,
            long resultMessageId,
            long resultMessageSequenceNo,
            long assistantMessageId,
            long assistantSequenceNo
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecuteCreateTicketToolResult(
                        toolExecutionId,
                        "9001",
                        "TKT-A1",
                        TicketStatus.OPEN,
                        resultMessageId,
                        resultMessageSequenceNo,
                        assistantMessageId,
                        assistantSequenceNo,
                        2,
                        PREPARED_AT,
                        false
                )
        );
    }

    @Test
    void shouldRejectNonConsecutiveSequences() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecuteCreateTicketToolResult(
                        7001L,
                        "9001",
                        "TKT-A1",
                        TicketStatus.OPEN,
                        8001L,
                        3L,
                        8002L,
                        5L,
                        2,
                        PREPARED_AT,
                        false
                )
        );
    }

    @Test
    void shouldRejectIdenticalMessageIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecuteCreateTicketToolResult(
                        7001L,
                        "9001",
                        "TKT-A1",
                        TicketStatus.OPEN,
                        8001L,
                        3L,
                        8001L,
                        4L,
                        2,
                        PREPARED_AT,
                        false
                )
        );
    }

    @ParameterizedTest
    @MethodSource("nonPositiveVersions")
    void shouldRejectNonPositiveVersions(int conversationVersion) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecuteCreateTicketToolResult(
                        7001L,
                        "9001",
                        "TKT-A1",
                        TicketStatus.OPEN,
                        8001L,
                        3L,
                        8002L,
                        4L,
                        conversationVersion,
                        PREPARED_AT,
                        false
                )
        );
    }

    @ParameterizedTest
    @MethodSource("blankTicketIds")
    void shouldRejectBlankTicketIds(String ticketId) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecuteCreateTicketToolResult(
                        7001L,
                        ticketId,
                        "TKT-A1",
                        TicketStatus.OPEN,
                        8001L,
                        3L,
                        8002L,
                        4L,
                        2,
                        PREPARED_AT,
                        false
                )
        );
    }

    @ParameterizedTest
    @MethodSource("blankTicketNos")
    void shouldRejectBlankTicketNos(String ticketNo) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecuteCreateTicketToolResult(
                        7001L,
                        "9001",
                        ticketNo,
                        TicketStatus.OPEN,
                        8001L,
                        3L,
                        8002L,
                        4L,
                        2,
                        PREPARED_AT,
                        false
                )
        );
    }

    @Test
    void shouldRejectNullTicketStatus() {
        assertThrows(
                NullPointerException.class,
                () -> new ExecuteCreateTicketToolResult(
                        7001L,
                        "9001",
                        "TKT-A1",
                        null,
                        8001L,
                        3L,
                        8002L,
                        4L,
                        2,
                        PREPARED_AT,
                        false
                )
        );
    }

    @Test
    void shouldRejectNullAssistantPreparedAt() {
        assertThrows(
                NullPointerException.class,
                () -> new ExecuteCreateTicketToolResult(
                        7001L,
                        "9001",
                        "TKT-A1",
                        TicketStatus.OPEN,
                        8001L,
                        3L,
                        8002L,
                        4L,
                        2,
                        null,
                        false
                )
        );
    }

    private static Stream<Arguments> nonPositiveIds() {
        return Stream.of(
                Arguments.of(0L, 8001L, 3L, 8002L, 4L),
                Arguments.of(-7001L, 8001L, 3L, 8002L, 4L),
                Arguments.of(7001L, 0L, 3L, 8002L, 4L),
                Arguments.of(7001L, -8001L, 3L, 8002L, 4L),
                Arguments.of(7001L, 8001L, 0L, 8002L, 4L),
                Arguments.of(7001L, 8001L, -3L, 8002L, 4L),
                Arguments.of(7001L, 8001L, 3L, 0L, 4L),
                Arguments.of(7001L, 8001L, 3L, -8002L, 4L),
                Arguments.of(7001L, 8001L, 3L, 8002L, 0L),
                Arguments.of(7001L, 8001L, 3L, 8002L, -4L)
        );
    }

    private static Stream<Arguments> nonPositiveVersions() {
        return Stream.of(
                Arguments.of(0),
                Arguments.of(-1),
                Arguments.of(Integer.MIN_VALUE)
        );
    }

    private static Stream<Arguments> blankTicketIds() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of(""),
                Arguments.of("   ")
        );
    }

    private static Stream<Arguments> blankTicketNos() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of(""),
                Arguments.of("   ")
        );
    }
}
