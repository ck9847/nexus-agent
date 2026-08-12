package com.nexusagent.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationTurnStreamingPropertiesTest {

    @Test
    void shouldAcceptValidConfiguration() {
        ConversationTurnStreamingProperties properties =
                new ConversationTurnStreamingProperties(
                        Duration.ofMinutes(2),
                        4,
                        16,
                        100
                );

        assertEquals(
                Duration.ofMinutes(2),
                properties.timeout()
        );
        assertEquals(4, properties.corePoolSize());
        assertEquals(16, properties.maxPoolSize());
        assertEquals(100, properties.queueCapacity());
    }

    @Test
    void shouldRejectNullTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamingProperties(
                        null,
                        4,
                        16,
                        100
                )
        );
    }

    @Test
    void shouldRejectNonPositiveTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamingProperties(
                        Duration.ZERO,
                        4,
                        16,
                        100
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamingProperties(
                        Duration.ofSeconds(-1),
                        4,
                        16,
                        100
                )
        );
    }

    @Test
    void shouldRejectZeroCorePoolSize() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamingProperties(
                        Duration.ofMinutes(2),
                        0,
                        16,
                        100
                )
        );
    }

    @Test
    void shouldRejectMaxBelowCore() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamingProperties(
                        Duration.ofMinutes(2),
                        8,
                        4,
                        100
                )
        );
    }

    @Test
    void shouldRejectNegativeQueueCapacity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationTurnStreamingProperties(
                        Duration.ofMinutes(2),
                        4,
                        16,
                        -1
                )
        );
    }
}
