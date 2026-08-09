package com.nexusagent.agent.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStatusTransitionPolicyTest {

    @ParameterizedTest
    @CsvSource({
            "DRAFT, ACTIVE",
            "ACTIVE, DISABLED",
            "DISABLED, ACTIVE"
    })
    void shouldAllowValidTransitions(
            AgentStatus currentStatus,
            AgentStatus targetStatus
    ) {
        assertTrue(
                AgentStatusTransitionPolicy.isAllowed(
                        currentStatus,
                        targetStatus
                )
        );

        assertDoesNotThrow(
                () -> AgentStatusTransitionPolicy
                        .requireAllowed(
                                currentStatus,
                                targetStatus
                        )
        );
    }

    @ParameterizedTest
    @CsvSource({
            "DRAFT, DRAFT",
            "DRAFT, DISABLED",
            "ACTIVE, DRAFT",
            "ACTIVE, ACTIVE",
            "DISABLED, DRAFT",
            "DISABLED, DISABLED"
    })
    void shouldRejectInvalidTransitions(
            AgentStatus currentStatus,
            AgentStatus targetStatus
    ) {
        assertFalse(
                AgentStatusTransitionPolicy.isAllowed(
                        currentStatus,
                        targetStatus
                )
        );

        assertThrows(
                InvalidAgentStatusTransitionException.class,
                () -> AgentStatusTransitionPolicy
                        .requireAllowed(
                                currentStatus,
                                targetStatus
                        )
        );
    }
}