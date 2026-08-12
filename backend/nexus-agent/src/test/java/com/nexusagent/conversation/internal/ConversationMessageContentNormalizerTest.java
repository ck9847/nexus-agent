package com.nexusagent.conversation.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationMessageContentNormalizerTest {

    @Test
    void shouldTrimAndReturnNormalizedContent() {
        assertEquals(
                "Hello, I need help.",
                ConversationMessageContentNormalizer.normalize(
                        "  Hello, I need help.  "
                )
        );
    }

    @Test
    void shouldPreserveInternalWhitespace() {
        assertEquals(
                "Hello   world",
                ConversationMessageContentNormalizer.normalize(
                        "  Hello   world\n"
                )
        );
    }

    @ParameterizedTest
    @NullSource
    void shouldRejectNullContent(String value) {
        assertThrows(
                IllegalArgumentException.class,
                () -> ConversationMessageContentNormalizer
                        .normalize(value)
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
                () -> ConversationMessageContentNormalizer
                        .normalize(value)
        );
    }

    @Test
    void shouldAcceptContentAtExactMaximumLength() {
        String content = "x".repeat(50_000);

        assertEquals(
                content,
                ConversationMessageContentNormalizer
                        .normalize(content)
        );
    }

    @Test
    void shouldRejectContentBeyondMaximumLength() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ConversationMessageContentNormalizer
                                .normalize("x".repeat(50_001))
                );

        assertEquals(
                "content must not exceed 50000 characters",
                exception.getMessage()
        );
    }
}
