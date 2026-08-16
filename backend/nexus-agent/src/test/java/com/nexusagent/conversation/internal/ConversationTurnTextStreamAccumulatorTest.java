package com.nexusagent.conversation.internal;

import com.nexusagent.conversation.api.ConversationTurnStreamEvent;
import com.nexusagent.conversation.api.ConversationTurnStreamHandler;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatModelStreamConsumerException;
import com.nexusagent.model.api.ChatModelStreamEvent;
import com.nexusagent.model.api.ChatTokenUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationTurnTextStreamAccumulatorTest {

    private static final ChatModelFinishReason FINISH_REASON =
            ChatModelFinishReason.STOP;

    private static final ChatTokenUsage USAGE =
            new ChatTokenUsage(12, 34);

    @Test
    void shouldConcatenateTextDeltasVerbatimAndForwardEach() {
        List<ConversationTurnStreamEvent> received =
                new ArrayList<>();

        ConversationTurnTextStreamAccumulator accumulator =
                new ConversationTurnTextStreamAccumulator(
                        received::add
                );

        accumulator.onEvent(
                new ChatModelStreamEvent.TextDelta("Hello")
        );
        accumulator.onEvent(
                new ChatModelStreamEvent.TextDelta(" world")
        );
        accumulator.onEvent(
                new ChatModelStreamEvent.Completed(
                        FINISH_REASON,
                        USAGE
                )
        );

        ConversationTurnTextStreamAccumulator.TextCompletion
                completion = accumulator.requireCompletion();

        assertEquals("Hello world", completion.content());
        assertEquals(FINISH_REASON, completion.finishReason());
        assertEquals(USAGE, completion.usage());

        assertEquals(
                List.of(
                        new ConversationTurnStreamEvent.TextDelta(
                                "Hello"
                        ),
                        new ConversationTurnStreamEvent.TextDelta(
                                " world"
                        )
                ),
                received
        );
    }

    @Test
    void shouldPreserveWhitespaceFragments() {
        List<ConversationTurnStreamEvent> received =
                new ArrayList<>();

        ConversationTurnTextStreamAccumulator accumulator =
                new ConversationTurnTextStreamAccumulator(
                        received::add
                );

        accumulator.onEvent(
                new ChatModelStreamEvent.TextDelta("  ")
        );
        accumulator.onEvent(
                new ChatModelStreamEvent.TextDelta("x")
        );
        accumulator.onEvent(
                new ChatModelStreamEvent.Completed(
                        FINISH_REASON,
                        USAGE
                )
        );

        assertEquals(
                "  x",
                accumulator.requireCompletion().content()
        );

        assertEquals(
                List.of(
                        new ConversationTurnStreamEvent.TextDelta(
                                "  "
                        ),
                        new ConversationTurnStreamEvent.TextDelta(
                                "x"
                        )
                ),
                received
        );
    }

    @Test
    void shouldOnlyForwardTextDeltasToDownstream() {
        List<ConversationTurnStreamEvent> received =
                new ArrayList<>();

        ConversationTurnTextStreamAccumulator accumulator =
                new ConversationTurnTextStreamAccumulator(
                        received::add
                );

        accumulator.onEvent(
                new ChatModelStreamEvent.TextDelta("Hi")
        );
        accumulator.onEvent(
                new ChatModelStreamEvent.Completed(
                        FINISH_REASON,
                        USAGE
                )
        );

        assertEquals(1, received.size());

        assertTrue(
                received.stream()
                        .allMatch(event -> event
                                instanceof
                                ConversationTurnStreamEvent.TextDelta)
        );

        assertEquals(
                "Hi",
                ((ConversationTurnStreamEvent.TextDelta)
                        received.get(0)).text()
        );
    }

    @Test
    void shouldRejectCompletionQueryWithoutCompletedEvent() {
        ConversationTurnTextStreamAccumulator accumulator =
                new ConversationTurnTextStreamAccumulator(
                        event -> {
                        }
                );

        accumulator.onEvent(
                new ChatModelStreamEvent.TextDelta("Hello")
        );

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                accumulator::requireCompletion
        );

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                exception.category()
        );
        assertEquals(
                "Chat model stream did not include "
                        + "a Completed event",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectSecondCompletedEvent() {
        ConversationTurnTextStreamAccumulator accumulator =
                new ConversationTurnTextStreamAccumulator(
                        event -> {
                        }
                );

        accumulator.onEvent(
                new ChatModelStreamEvent.Completed(
                        FINISH_REASON,
                        USAGE
                )
        );

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> accumulator.onEvent(
                        new ChatModelStreamEvent.Completed(
                                FINISH_REASON,
                                USAGE
                        )
                )
        );

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                exception.category()
        );
        assertEquals(
                "Chat model emitted events after completion",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectTextDeltaAfterCompletedEvent() {
        ConversationTurnTextStreamAccumulator accumulator =
                new ConversationTurnTextStreamAccumulator(
                        event -> {
                        }
                );

        accumulator.onEvent(
                new ChatModelStreamEvent.Completed(
                        FINISH_REASON,
                        USAGE
                )
        );

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> accumulator.onEvent(
                        new ChatModelStreamEvent.TextDelta("late")
                )
        );

        assertEquals(
                "Chat model emitted events after completion",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectToolCallDelta() {
        ConversationTurnTextStreamAccumulator accumulator =
                new ConversationTurnTextStreamAccumulator(
                        event -> {
                        }
                );

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> accumulator.onEvent(
                        new ChatModelStreamEvent.ToolCallDelta(
                                0,
                                "call_",
                                null,
                                null
                        )
                )
        );

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                exception.category()
        );
        assertEquals(
                "Tool calls are not supported "
                        + "for this conversation turn",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectToolCallsFinishReason() {
        ConversationTurnTextStreamAccumulator accumulator =
                new ConversationTurnTextStreamAccumulator(
                        event -> {
                        }
                );

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> accumulator.onEvent(
                        new ChatModelStreamEvent.Completed(
                                ChatModelFinishReason.TOOL_CALLS,
                                USAGE
                        )
                )
        );

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                exception.category()
        );
        assertEquals(
                "Tool calls are not supported "
                        + "for this conversation turn",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectBlankFinalContent() {
        ConversationTurnTextStreamAccumulator accumulator =
                new ConversationTurnTextStreamAccumulator(
                        event -> {
                        }
                );

        accumulator.onEvent(
                new ChatModelStreamEvent.TextDelta("   ")
        );
        accumulator.onEvent(
                new ChatModelStreamEvent.Completed(
                        FINISH_REASON,
                        USAGE
                )
        );

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                accumulator::requireCompletion
        );

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                exception.category()
        );
        assertEquals(
                "Chat model assistant response "
                        + "must not be blank",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectOverflowWithoutForwardingTheOverflowingFragment() {
        List<ConversationTurnStreamEvent> received =
                new ArrayList<>();

        ConversationTurnTextStreamAccumulator accumulator =
                new ConversationTurnTextStreamAccumulator(
                        received::add
                );

        accumulator.onEvent(
                new ChatModelStreamEvent.TextDelta(
                        "a".repeat(49_990)
                )
        );
        accumulator.onEvent(
                new ChatModelStreamEvent.TextDelta(
                        "b".repeat(10)
                )
        );

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> accumulator.onEvent(
                        new ChatModelStreamEvent.TextDelta("c")
                )
        );

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                exception.category()
        );
        assertEquals(
                "Chat model assistant response is too large",
                exception.getMessage()
        );

        assertEquals(2, received.size());
    }

    @Test
    void shouldRejectNullStreamEvent() {
        ConversationTurnTextStreamAccumulator accumulator =
                new ConversationTurnTextStreamAccumulator(
                        event -> {
                        }
                );

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> accumulator.onEvent(null)
        );

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                exception.category()
        );
        assertEquals(
                "Chat model emitted a null stream event",
                exception.getMessage()
        );
    }

    @Test
    void shouldWrapDownstreamFailureInConsumerException() {
        ConversationTurnStreamHandler failingHandler =
                event -> {
                    throw new IllegalStateException(
                            "downstream boom"
                    );
                };

        ConversationTurnTextStreamAccumulator accumulator =
                new ConversationTurnTextStreamAccumulator(
                        failingHandler
                );

        ChatModelStreamConsumerException exception =
                assertThrows(
                        ChatModelStreamConsumerException.class,
                        () -> accumulator.onEvent(
                                new ChatModelStreamEvent.TextDelta(
                                        "Hello"
                                )
                        )
                );

        assertEquals(
                "Conversation turn stream consumer failed",
                exception.getMessage()
        );
        assertTrue(
                exception.getCause()
                        instanceof IllegalStateException
        );
        assertEquals(
                "downstream boom",
                exception.getCause().getMessage()
        );
    }
}
