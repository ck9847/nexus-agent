package com.nexusagent.conversation.internal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationIdParserTest {

    private static final String ERROR_MESSAGE =
            "conversationId must be a positive integer";

    @ParameterizedTest
    @CsvSource({
            "901, 901",
            "' 901 ', 901",
            "000901, 901"
    })
    void shouldParseValidConversationIds(
            String value,
            long expected
    ) {
        assertEquals(
                expected,
                ConversationIdParser.parse(value)
        );
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            " ",
            "abc",
            "1.5",
            "-1",
            "0",
            "9223372036854775808"
    })
    void shouldRejectInvalidConversationIds(String value) {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ConversationIdParser.parse(value)
                );

        assertEquals(ERROR_MESSAGE, exception.getMessage());
    }
}
