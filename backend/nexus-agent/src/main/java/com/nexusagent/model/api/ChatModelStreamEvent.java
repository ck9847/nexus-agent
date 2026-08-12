package com.nexusagent.model.api;

import java.util.Objects;

public sealed interface ChatModelStreamEvent
        permits ChatModelStreamEvent.TextDelta,
        ChatModelStreamEvent.ToolCallDelta,
        ChatModelStreamEvent.Completed {

    record TextDelta(String text)
            implements ChatModelStreamEvent {

        public TextDelta {
            Objects.requireNonNull(
                    text,
                    "text must not be null"
            );

            if (text.isEmpty()) {
                throw new IllegalArgumentException(
                        "text must not be empty"
                );
            }
        }
    }

    record ToolCallDelta(
            int index,
            String callIdFragment,
            String nameFragment,
            String argumentsFragment
    ) implements ChatModelStreamEvent {

        public ToolCallDelta {
            if (index < 0) {
                throw new IllegalArgumentException(
                        "index must not be negative"
                );
            }

            if (isEmpty(callIdFragment)
                    && isEmpty(nameFragment)
                    && isEmpty(argumentsFragment)) {
                throw new IllegalArgumentException(
                        "tool call delta must contain a fragment"
                );
            }
        }

        private static boolean isEmpty(String value) {
            return value == null || value.isEmpty();
        }
    }

    record Completed(
            ChatModelFinishReason finishReason,
            ChatTokenUsage usage
    ) implements ChatModelStreamEvent {

        public Completed {
            Objects.requireNonNull(
                    finishReason,
                    "finishReason must not be null"
            );
            Objects.requireNonNull(
                    usage,
                    "usage must not be null"
            );
        }
    }
}