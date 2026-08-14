package com.nexusagent.conversation.internal;

import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatModelToolCall;
import com.nexusagent.model.api.ChatTokenUsage;

import java.util.Objects;

public sealed interface ConversationTurnModelCompletion
        permits ConversationTurnModelCompletion.Text,
        ConversationTurnModelCompletion.ToolCall {

    ChatTokenUsage usage();

    record Text(
            String content,
            ChatModelFinishReason finishReason,
            ChatTokenUsage usage
    ) implements ConversationTurnModelCompletion {

        public Text {
            Objects.requireNonNull(content);
            Objects.requireNonNull(finishReason);
            Objects.requireNonNull(usage);

            if (content.isBlank()) {
                throw new IllegalArgumentException(
                        "content must not be blank"
                );
            }

            if (finishReason
                    == ChatModelFinishReason.TOOL_CALLS) {
                throw new IllegalArgumentException(
                        "text completion cannot use TOOL_CALLS"
                );
            }
        }
    }

    record ToolCall(
            ChatModelToolCall call,
            ChatTokenUsage usage
    ) implements ConversationTurnModelCompletion {

        public ToolCall {
            Objects.requireNonNull(call);
            Objects.requireNonNull(usage);
        }
    }
}