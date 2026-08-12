package com.nexusagent.conversation.internal;

import com.nexusagent.conversation.api.ConversationTurnStreamEvent;
import com.nexusagent.conversation.api.ConversationTurnStreamHandler;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatModelStreamEvent;
import com.nexusagent.model.api.ChatModelStreamHandler;
import com.nexusagent.model.api.ChatTokenUsage;

import java.util.Objects;

/**
 * Accumulates the text of a chat model completion stream and forwards
 * each text fragment verbatim to a downstream handler.
 *
 * <p>The current version accepts a pure text stream only:
 * <ul>
 *     <li>each {@code TextDelta} is appended and forwarded as-is;</li>
 *     <li>at most 50,000 characters may accumulate;</li>
 *     <li>exactly one {@code Completed} event is allowed and no event
 *         may follow it;</li>
 *     <li>tool calls ({@code ToolCallDelta} and a
 *         {@code TOOL_CALLS} finish reason) are rejected;</li>
 *     <li>the final content must not be blank.</li>
 * </ul>
 *
 * <p>Stream protocol violations are surfaced as a
 * {@link ChatModelException} with category
 * {@link ChatModelErrorCategory#MALFORMED_RESPONSE}, while failures
 * thrown by the downstream
 * {@link ConversationTurnStreamHandler} are wrapped in a
 * {@link ConversationTurnStreamConsumerException} so that they are not
 * mistaken for model provider failures.
 */
final class ConversationTurnTextStreamAccumulator
        implements ChatModelStreamHandler {

    private static final int MAX_CONTENT_LENGTH = 50_000;

    private final ConversationTurnStreamHandler downstream;
    private final StringBuilder content = new StringBuilder();
    private ChatModelStreamEvent.Completed completion;

    ConversationTurnTextStreamAccumulator(
            ConversationTurnStreamHandler downstream
    ) {
        this.downstream = Objects.requireNonNull(
                downstream,
                "downstream must not be null"
        );
    }

    @Override
    public void onEvent(ChatModelStreamEvent event) {
        accept(event);
    }

    void accept(ChatModelStreamEvent event) {
        if (event == null) {
            throw malformed(
                    "Chat model emitted a null stream event"
            );
        }

        if (completion != null) {
            throw malformed(
                    "Chat model emitted events after completion"
            );
        }

        if (event instanceof ChatModelStreamEvent.TextDelta delta) {
            appendText(delta.text());
            forward(delta.text());
            return;
        }

        if (event instanceof ChatModelStreamEvent.ToolCallDelta) {
            throw malformed(
                    "Tool calls are not supported "
                            + "for this conversation turn"
            );
        }

        ChatModelStreamEvent.Completed completed =
                (ChatModelStreamEvent.Completed) event;

        if (completed.finishReason()
                == ChatModelFinishReason.TOOL_CALLS) {
            throw malformed(
                    "Tool calls are not supported "
                            + "for this conversation turn"
            );
        }

        this.completion = completed;
    }

    TextCompletion requireCompletion() {
        if (completion == null) {
            throw malformed(
                    "Chat model stream did not include "
                            + "a Completed event"
            );
        }

        String accumulated = content.toString();

        if (accumulated.isBlank()) {
            throw malformed(
                    "Chat model assistant response "
                            + "must not be blank"
            );
        }

        return new TextCompletion(
                accumulated,
                completion.finishReason(),
                completion.usage()
        );
    }

    private void appendText(String text) {
        if ((long) content.length() + text.length()
                > MAX_CONTENT_LENGTH) {
            throw malformed(
                    "Chat model assistant response "
                            + "is too large"
            );
        }

        content.append(text);
    }

    private void forward(String text) {
        try {
            downstream.onEvent(
                    new ConversationTurnStreamEvent.TextDelta(text)
            );
        } catch (RuntimeException cause) {
            throw new ConversationTurnStreamConsumerException(
                    cause
            );
        }
    }

    private static ChatModelException malformed(
            String safeMessage
    ) {
        return new ChatModelException(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                safeMessage
        );
    }

    record TextCompletion(
            String content,
            ChatModelFinishReason finishReason,
            ChatTokenUsage usage
    ) {
    }
}

final class ConversationTurnStreamConsumerException
        extends RuntimeException {

    ConversationTurnStreamConsumerException(
            Throwable cause
    ) {
        super(
                "Conversation turn stream consumer failed",
                cause
        );
    }
}
