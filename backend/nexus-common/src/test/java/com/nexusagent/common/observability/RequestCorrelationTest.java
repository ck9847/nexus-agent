package com.nexusagent.common.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestCorrelationTest {

    @Test
    void shouldCreateValidCorrelation() {
        RequestCorrelation correlation =
                new RequestCorrelation(
                        "req-1.2_3",
                        "trace-A_b.9",
                        "192.168.0.1"
                );

        assertAll(
                () -> assertEquals(
                        "req-1.2_3",
                        correlation.requestId()
                ),
                () -> assertEquals(
                        "trace-A_b.9",
                        correlation.traceId()
                ),
                () -> assertEquals(
                        "192.168.0.1",
                        correlation.ipAddress()
                )
        );
    }

    @Test
    void shouldAcceptBoundaryLengthIds() {
        RequestCorrelation minimum =
                new RequestCorrelation(
                        "a",
                        "0",
                        "127.0.0.1"
                );

        assertEquals("a", minimum.requestId());
        assertEquals("0", minimum.traceId());

        String maximum = "A".repeat(64);

        RequestCorrelation maximumLength =
                new RequestCorrelation(
                        maximum,
                        maximum,
                        "127.0.0.1"
                );

        assertEquals(maximum, maximumLength.requestId());
        assertEquals(maximum, maximumLength.traceId());
    }

    @Test
    void shouldAcceptIpv4Address() {
        RequestCorrelation correlation =
                new RequestCorrelation(
                        "req-ipv4",
                        "trace-ipv4",
                        "192.168.10.255"
                );

        assertEquals(
                "192.168.10.255",
                correlation.ipAddress()
        );
    }

    @Test
    void shouldAcceptIpv6Address() {
        String ipv6 =
                "2001:0db8:85a3:0000:0000:8a2e:0370:7334";

        RequestCorrelation correlation =
                new RequestCorrelation(
                        "req-ipv6",
                        "trace-ipv6",
                        ipv6
                );

        assertEquals(ipv6, correlation.ipAddress());
    }

    @Test
    void shouldRejectNullFields() {
        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new RequestCorrelation(
                                null,
                                "trace-1",
                                "127.0.0.1"
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new RequestCorrelation(
                                "req-1",
                                null,
                                "127.0.0.1"
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new RequestCorrelation(
                                "req-1",
                                "trace-1",
                                null
                        )
                )
        );
    }

    @Test
    void shouldRejectBlankFields() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new RequestCorrelation(
                                "",
                                "trace-1",
                                "127.0.0.1"
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new RequestCorrelation(
                                "   ",
                                "trace-1",
                                "127.0.0.1"
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new RequestCorrelation(
                                "req-1",
                                "",
                                "127.0.0.1"
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new RequestCorrelation(
                                "req-1",
                                "trace-1",
                                ""
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new RequestCorrelation(
                                "req-1",
                                "trace-1",
                                "   "
                        )
                )
        );
    }

    @Test
    void shouldRejectEmbeddedWhitespace() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new RequestCorrelation(
                                "req 1",
                                "trace-1",
                                "127.0.0.1"
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new RequestCorrelation(
                                "req-1",
                                "trace\t1",
                                "127.0.0.1"
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new RequestCorrelation(
                                "req-1",
                                "trace-1",
                                "192.168.0. 1"
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new RequestCorrelation(
                                "req-1",
                                "trace-1",
                                "192.168.0.1\t"
                        )
                )
        );
    }

    @Test
    void shouldRejectOversizedFields() {
        String oversizedId = "a".repeat(65);
        String oversizedIp = "a".repeat(46);

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new RequestCorrelation(
                                oversizedId,
                                "trace-1",
                                "127.0.0.1"
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new RequestCorrelation(
                                "req-1",
                                oversizedId,
                                "127.0.0.1"
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new RequestCorrelation(
                                "req-1",
                                "trace-1",
                                oversizedIp
                        )
                )
        );
    }

    @Test
    void shouldRejectIllegalIdCharacters() {
        String[] illegalIds = {
                "req/1",
                "req:1",
                "req@1",
                "req!1",
                "req#1",
                "req\u00011"
        };

        for (String illegalId : illegalIds) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new RequestCorrelation(
                            illegalId,
                            "trace-1",
                            "127.0.0.1"
                    ),
                    "requestId should be rejected: "
                            + illegalId
            );

            assertThrows(
                    IllegalArgumentException.class,
                    () -> new RequestCorrelation(
                            "req-1",
                            illegalId,
                            "127.0.0.1"
                    ),
                    "traceId should be rejected: "
                            + illegalId
            );
        }
    }

    @Test
    void shouldRejectControlCharactersInIpAddress() {
        String[] illegalAddresses = {
                "192.168.0.1\n",
                "192.168.0.1\r",
                "192.168.0.\u00071"
        };

        for (String illegalAddress : illegalAddresses) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new RequestCorrelation(
                            "req-1",
                            "trace-1",
                            illegalAddress
                    ),
                    "ipAddress should be rejected"
            );
        }
    }

    @Test
    void shouldExposeImmutableValues() {
        RequestCorrelation correlation =
                new RequestCorrelation(
                        "req-1",
                        "trace-1",
                        "10.0.0.1"
                );

        // String 本身不可变：重复读取必须返回同一值。
        assertAll(
                () -> assertEquals(
                        correlation.requestId(),
                        correlation.requestId()
                ),
                () -> assertEquals(
                        correlation.traceId(),
                        correlation.traceId()
                ),
                () -> assertEquals(
                        correlation.ipAddress(),
                        correlation.ipAddress()
                )
        );
    }
}
