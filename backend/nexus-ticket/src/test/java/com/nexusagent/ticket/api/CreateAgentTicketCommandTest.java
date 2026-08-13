package com.nexusagent.ticket.api;

import com.nexusagent.ticket.domain.TicketPriority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CreateAgentTicketCommandTest {

    @Test
    void shouldAcceptValidCommand() {
        CreateAgentTicketCommand command =
                new CreateAgentTicketCommand(
                        202L,
                        101L,
                        500L,
                        7001L,
                        "  Server unavailable  ",
                        "  Cannot connect to production.  ",
                        TicketPriority.HIGH
                );

        assertEquals(202L, command.tenantId());
        assertEquals(101L, command.requesterUserId());
        assertEquals(500L, command.createdByAgentId());
        assertEquals(7001L, command.toolExecutionId());
        assertEquals(
                "  Server unavailable  ",
                command.title()
        );
        assertEquals(
                "  Cannot connect to production.  ",
                command.description()
        );
        assertEquals(TicketPriority.HIGH, command.priority());
    }

    @ParameterizedTest
    @MethodSource("nonPositiveIds")
    void shouldRejectNonPositiveIds(
            long tenantId,
            long requesterUserId,
            long createdByAgentId,
            long toolExecutionId
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CreateAgentTicketCommand(
                        tenantId,
                        requesterUserId,
                        createdByAgentId,
                        toolExecutionId,
                        "title",
                        "description",
                        TicketPriority.HIGH
                )
        );
    }

    private static Stream<Arguments> nonPositiveIds() {
        return Stream.of(
                Arguments.of(0L, 101L, 500L, 7001L),
                Arguments.of(-202L, 101L, 500L, 7001L),
                Arguments.of(202L, 0L, 500L, 7001L),
                Arguments.of(202L, -101L, 500L, 7001L),
                Arguments.of(202L, 101L, 0L, 7001L),
                Arguments.of(202L, 101L, -500L, 7001L),
                Arguments.of(202L, 101L, 500L, 0L),
                Arguments.of(202L, 101L, 500L, -7001L)
        );
    }
}
