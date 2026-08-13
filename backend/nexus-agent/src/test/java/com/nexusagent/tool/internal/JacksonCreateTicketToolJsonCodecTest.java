package com.nexusagent.tool.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.ticket.domain.TicketPriority;
import com.nexusagent.ticket.domain.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JacksonCreateTicketToolJsonCodecTest {

    private final JacksonCreateTicketToolJsonCodec codec =
            new JacksonCreateTicketToolJsonCodec(
                    new ObjectMapper()
            );

    @Test
    void shouldDecodeArguments() {
        CreateTicketToolArguments arguments =
                codec.decodeArguments(
                        """
                        {
                            "title": "Server down",
                            "description": "Cannot connect.",
                            "priority": "HIGH"
                        }
                        """
                );

        assertEquals("Server down", arguments.title());
        assertEquals(
                "Cannot connect.",
                arguments.description()
        );
        assertEquals(
                TicketPriority.HIGH,
                arguments.priority()
        );
    }

    @Test
    void shouldRejectUnknownArgumentFields() {
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decodeArguments(
                        """
                        {
                            "title": "Server down",
                            "description": "Cannot connect.",
                            "priority": "HIGH",
                            "tenantId": 123
                        }
                        """
                )
        );
    }

    @Test
    void shouldRejectIllegalPriority() {
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decodeArguments(
                        """
                        {
                            "title": "Server down",
                            "description": "Cannot connect.",
                            "priority": "URGENTLY"
                        }
                        """
                )
        );
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "   ",
            "[]",
            "[1,2]",
            "42",
            "{\"title\":",
            "not json",
            """
            {"title":"x","description":"y","priority":"HIGH"} garbage
            """
    })
    void shouldRejectNullBlankOrMalformedArgumentJson(
            String json
    ) {
        if (json == null) {
            assertThrows(
                    NullPointerException.class,
                    () -> codec.decodeArguments(json)
            );
            return;
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decodeArguments(json)
        );
    }

    @Test
    void shouldRoundTripOutput() {
        CreateTicketToolOutput output =
                new CreateTicketToolOutput(
                        "9001",
                        "TKT-A1",
                        TicketStatus.OPEN
                );

        String json = codec.encodeOutput(output);

        CreateTicketToolOutput decoded =
                codec.decodeOutput(json);

        assertEquals(output, decoded);
    }

    @Test
    void shouldDecodeOutputAndTrimTicketNo() {
        CreateTicketToolOutput decoded =
                codec.decodeOutput(
                        """
                        {
                            "ticketId": "9001",
                            "ticketNo": "  TKT-A1  ",
                            "status": "OPEN"
                        }
                        """
                );

        assertEquals("9001", decoded.ticketId());
        assertEquals("TKT-A1", decoded.ticketNo());
        assertEquals(TicketStatus.OPEN, decoded.status());
    }

    @Test
    void shouldRejectUnknownOutputFields() {
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decodeOutput(
                        """
                        {
                            "ticketId": "9001",
                            "ticketNo": "TKT-A1",
                            "status": "OPEN",
                            "secret": "boom"
                        }
                        """
                )
        );
    }

    @Test
    void shouldRejectInvalidOutputTicketId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decodeOutput(
                        """
                        {
                            "ticketId": "abc",
                            "ticketNo": "TKT-A1",
                            "status": "OPEN"
                        }
                        """
                )
        );
    }

    @Test
    void shouldRejectInvalidOutputStatus() {
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decodeOutput(
                        """
                        {
                            "ticketId": "9001",
                            "ticketNo": "TKT-A1",
                            "status": "UNKNOWN"
                        }
                        """
                )
        );
    }

    @ParameterizedTest
    @MethodSource("invalidOutputJsons")
    void shouldRejectNullBlankOrMalformedOutputJson(
            String json,
            Class<? extends Throwable> expected
    ) {
        assertThrows(
                expected,
                () -> codec.decodeOutput(json)
        );
    }

    @Test
    void shouldRejectNullOutputOnEncode() {
        assertThrows(
                NullPointerException.class,
                () -> codec.encodeOutput(null)
        );
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments>
    invalidOutputJsons() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        null,
                        NullPointerException.class
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "",
                        IllegalArgumentException.class
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "   ",
                        IllegalArgumentException.class
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "[]",
                        IllegalArgumentException.class
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        "{\"ticketId\":",
                        IllegalArgumentException.class
                )
        );
    }
}
