package com.nexusagent.common.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentActorTest {

    @Test
    void shouldCreateAuthenticatedActor() {
        CurrentActor actor = new CurrentActor(
                101,
                202,
                "admin",
                Set.of("ADMIN")
        );

        assertAll(
                () -> assertEquals(101, actor.userId()),
                () -> assertEquals(202, actor.tenantId()),
                () -> assertEquals("admin", actor.username()),
                () -> assertTrue(actor.hasRole("ADMIN"))
        );
    }

    @Test
    void shouldRejectInvalidActor() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new CurrentActor(
                                0,
                                202,
                                "admin",
                                Set.of("ADMIN")
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new CurrentActor(
                                101,
                                0,
                                "admin",
                                Set.of("ADMIN")
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new CurrentActor(
                                101,
                                202,
                                " ",
                                Set.of("ADMIN")
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new CurrentActor(
                                101,
                                202,
                                "admin",
                                Set.of("")
                        )
                )
        );
    }
}