package com.nexusagent.model.api;

import java.util.List;
import java.util.Objects;

public record ChatModelMessage(
        ChatModelRole role,
        String content,
        List<ChatModelToolCall> toolCalls,
        String toolCallId
) {

    private static final int MAX_CONTENT_LENGTH = 50_000;

    public ChatModelMessage {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(
                toolCalls,
                "toolCalls must not be null"
        );

        toolCalls = List.copyOf(toolCalls);

        if (content != null) {
            if (content.isBlank()) {
                throw new IllegalArgumentException(
                        "content must not be blank"
                );
            }

            if (content.length() > MAX_CONTENT_LENGTH) {
                throw new IllegalArgumentException(
                        "content must not exceed 50000 characters"
                );
            }
        }

        if (toolCallId != null) {
            toolCallId = toolCallId.trim();

            if (toolCallId.isBlank()) {
                throw new IllegalArgumentException(
                        "toolCallId must not be blank"
                );
            }

            if (toolCallId.length() > 128) {
                throw new IllegalArgumentException(
                        "toolCallId must not exceed 128 characters"
                );
            }
        }

        switch (role) {
            case USER -> {
                requireContent(content);
                requireNoToolCalls(toolCalls);
                requireNoToolCallId(toolCallId);
            }
            case ASSISTANT -> {
                if (content == null && toolCalls.isEmpty()) {
                    throw new IllegalArgumentException(
                            "assistant message must contain content "
                                    + "or tool calls"
                    );
                }

                requireNoToolCallId(toolCallId);
            }
            case TOOL -> {
                requireContent(content);
                requireNoToolCalls(toolCalls);

                if (toolCallId == null) {
                    throw new IllegalArgumentException(
                            "tool message must contain toolCallId"
                    );
                }
            }
        }
    }

    public static ChatModelMessage user(String content) {
        return new ChatModelMessage(
                ChatModelRole.USER,
                content,
                List.of(),
                null
        );
    }

    public static ChatModelMessage assistant(String content) {
        return new ChatModelMessage(
                ChatModelRole.ASSISTANT,
                content,
                List.of(),
                null
        );
    }

    private static void requireContent(String content) {
        if (content == null) {
            throw new IllegalArgumentException(
                    "message content must not be null"
            );
        }
    }

    private static void requireNoToolCalls(
            List<ChatModelToolCall> toolCalls
    ) {
        if (!toolCalls.isEmpty()) {
            throw new IllegalArgumentException(
                    "message role does not allow tool calls"
            );
        }
    }

    private static void requireNoToolCallId(
            String toolCallId
    ) {
        if (toolCallId != null) {
            throw new IllegalArgumentException(
                    "message role does not allow toolCallId"
            );
        }
    }
}