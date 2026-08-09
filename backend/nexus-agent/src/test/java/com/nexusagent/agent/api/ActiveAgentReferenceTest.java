package com.nexusagent.agent.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActiveAgentReferenceTest {

    @Test
    void shouldCreateValidReference() {
        ActiveAgentReference reference =
                new ActiveAgentReference(
                        101L,
                        202L,
                        "support-agent"
                );

        assertAll(
                () -> assertEquals(
                        101L,
                        reference.agentId()
                ),
                () -> assertEquals(
                        202L,
                        reference.tenantId()
                ),
                () -> assertEquals(
                        "support-agent",
                        reference.code()
                )
        );
    }

    @Test
    void shouldRejectInvalidIds() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ActiveAgentReference(
                                0L,
                                202L,
                                "support-agent"
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ActiveAgentReference(
                                101L,
                                0L,
                                "support-agent"
                        )
                )
        );
    }

    @Test
    void shouldRejectMissingCode() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ActiveAgentReference(
                                101L,
                                202L,
                                null
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new ActiveAgentReference(
                                101L,
                                202L,
                                " "
                        )
                )
        );
    }
}