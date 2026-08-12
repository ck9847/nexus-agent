package com.nexusagent.conversation.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationAssistantContentValidatorTest {

    @Test
    void shouldPreserveLeadingAndTrailingWhitespace() {
        assertSame(
                "  Hello world  ",
                ConversationAssistantContentValidator
                        .requireValid("  Hello world  ")
        );
    }

    @Test
    void shouldReturnContentUnchanged() {
        assertEquals(
                "Hello world",
                ConversationAssistantContentValidator
                        .requireValid("Hello world")
        );
    }

    @ParameterizedTest
    @NullSource
    void shouldRejectNullContent(String value) {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ConversationAssistantContentValidator
                                .requireValid(value)
                );

        assertEquals(
                "assistant content must not be null",
                exception.getMessage()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "\t",
            "\n"
    })
    void shouldRejectBlankContent(String value) {
        assertThrows(
                IllegalArgumentException.class,
                () -> ConversationAssistantContentValidator
                        .requireValid(value)
        );
    }

    @Test
    void shouldAcceptContentAtExactMaximumLength() {
        String content = "x".repeat(50_000);

        assertEquals(
                content,
                ConversationAssistantContentValidator
                        .requireValid(content)
        );
    }

    @Test
    void shouldRejectContentBeyondMaximumLength() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ConversationAssistantContentValidator
                                .requireValid("x".repeat(50_001))
                );

        assertEquals(
                "assistant content must not exceed "
                        + "50000 characters",
                exception.getMessage()
        );
    }
}
