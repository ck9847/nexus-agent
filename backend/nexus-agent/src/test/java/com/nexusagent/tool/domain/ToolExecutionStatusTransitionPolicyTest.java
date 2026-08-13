package com.nexusagent.tool.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutionStatusTransitionPolicyTest {

    private static final Set<List<ToolExecutionStatus>>
            ALLOWED_TRANSITIONS =
            Set.of(
                    List.of(
                            ToolExecutionStatus.PENDING,
                            ToolExecutionStatus.RUNNING
                    ),
                    List.of(
                            ToolExecutionStatus.PENDING,
                            ToolExecutionStatus.WAITING_APPROVAL
                    ),
                    List.of(
                            ToolExecutionStatus.PENDING,
                            ToolExecutionStatus.FAILED
                    ),
                    List.of(
                            ToolExecutionStatus.PENDING,
                            ToolExecutionStatus.CANCELLED
                    ),
                    List.of(
                            ToolExecutionStatus.WAITING_APPROVAL,
                            ToolExecutionStatus.RUNNING
                    ),
                    List.of(
                            ToolExecutionStatus.WAITING_APPROVAL,
                            ToolExecutionStatus.CANCELLED
                    ),
                    List.of(
                            ToolExecutionStatus.RUNNING,
                            ToolExecutionStatus.SUCCEEDED
                    ),
                    List.of(
                            ToolExecutionStatus.RUNNING,
                            ToolExecutionStatus.FAILED
                    ),
                    List.of(
                            ToolExecutionStatus.RUNNING,
                            ToolExecutionStatus.CANCELLED
                    )
            );

    @ParameterizedTest
    @MethodSource("allowedTransitions")
    void shouldAllowSupportedTransitions(
            ToolExecutionStatus current,
            ToolExecutionStatus target
    ) {
        assertTrue(
                ToolExecutionStatusTransitionPolicy
                        .isAllowed(current, target)
        );

        assertDoesNotThrow(() ->
                ToolExecutionStatusTransitionPolicy
                        .requireAllowed(current, target)
        );
    }

    @ParameterizedTest
    @MethodSource("disallowedTransitions")
    void shouldRejectUnsupportedTransitions(
            ToolExecutionStatus current,
            ToolExecutionStatus target
    ) {
        assertFalse(
                ToolExecutionStatusTransitionPolicy
                        .isAllowed(current, target)
        );

        assertThrows(
                InvalidToolExecutionStatusTransitionException.class,
                () -> ToolExecutionStatusTransitionPolicy
                        .requireAllowed(current, target)
        );
    }

    @ParameterizedTest
    @EnumSource(ToolExecutionStatus.class)
    void shouldNotTreatSameStatusAsTransition(
            ToolExecutionStatus status
    ) {
        assertFalse(
                ToolExecutionStatusTransitionPolicy
                        .isAllowed(status, status)
        );
    }

    @ParameterizedTest
    @MethodSource("terminalTransitions")
    void shouldRejectTransitionsFromTerminalStates(
            ToolExecutionStatus current,
            ToolExecutionStatus target
    ) {
        assertFalse(
                ToolExecutionStatusTransitionPolicy
                        .isAllowed(current, target)
        );

        assertThrows(
                InvalidToolExecutionStatusTransitionException.class,
                () -> ToolExecutionStatusTransitionPolicy
                        .requireAllowed(current, target)
        );
    }

    @ParameterizedTest
    @MethodSource("nullStatuses")
    void shouldRejectNullStatuses(
            ToolExecutionStatus current,
            ToolExecutionStatus target
    ) {
        assertThrows(
                NullPointerException.class,
                () -> ToolExecutionStatusTransitionPolicy
                        .isAllowed(current, target)
        );

        assertThrows(
                NullPointerException.class,
                () -> ToolExecutionStatusTransitionPolicy
                        .requireAllowed(current, target)
        );
    }

    @Test
    void shouldPreserveStatusesInException() {
        InvalidToolExecutionStatusTransitionException
                exception =
                assertThrows(
                        InvalidToolExecutionStatusTransitionException.class,
                        () -> ToolExecutionStatusTransitionPolicy
                                .requireAllowed(
                                        ToolExecutionStatus.SUCCEEDED,
                                        ToolExecutionStatus.RUNNING
                                )
                );

        assertEquals(
                ToolExecutionStatus.SUCCEEDED,
                exception.currentStatus()
        );
        assertEquals(
                ToolExecutionStatus.RUNNING,
                exception.targetStatus()
        );
    }

    private static Stream<Arguments> allowedTransitions() {
        return ALLOWED_TRANSITIONS.stream()
                .map(pair -> Arguments.of(
                        pair.get(0),
                        pair.get(1)
                ));
    }

    private static Stream<Arguments> disallowedTransitions() {
        List<Arguments> disallowed = new ArrayList<>();

        for (ToolExecutionStatus current
                : ToolExecutionStatus.values()) {
            for (ToolExecutionStatus target
                    : ToolExecutionStatus.values()) {
                if (!ALLOWED_TRANSITIONS.contains(
                        List.of(current, target)
                )) {
                    disallowed.add(
                            Arguments.of(current, target)
                    );
                }
            }
        }

        return disallowed.stream();
    }

    private static Stream<Arguments> terminalTransitions() {
        return Stream.of(
                ToolExecutionStatus.SUCCEEDED,
                ToolExecutionStatus.FAILED,
                ToolExecutionStatus.CANCELLED
        ).flatMap(terminal ->
                Arrays.stream(ToolExecutionStatus.values())
                        .map(target -> Arguments.of(
                                terminal,
                                target
                        ))
        );
    }

    private static Stream<Arguments> nullStatuses() {
        return Stream.of(
                Arguments.of(
                        null,
                        ToolExecutionStatus.PENDING
                ),
                Arguments.of(
                        ToolExecutionStatus.PENDING,
                        null
                ),
                Arguments.of(null, null)
        );
    }
}
