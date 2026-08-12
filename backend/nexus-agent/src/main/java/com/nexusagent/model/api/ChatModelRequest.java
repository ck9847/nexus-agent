package com.nexusagent.model.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ChatModelRequest(
        String modelName,
        String systemPrompt,
        ChatModelOptions options,
        List<ChatModelMessage> messages,
        List<ChatToolDefinition> tools
) {

    private static final int MAX_MODEL_NAME_LENGTH = 128;
    private static final int MAX_SYSTEM_PROMPT_LENGTH = 50_000;
    private static final int MAX_MESSAGES = 1_000;
    private static final int MAX_TOOLS = 64;

    public ChatModelRequest {
        modelName = normalizeModelName(modelName);

        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException(
                    "systemPrompt must not be blank"
            );
        }

        if (systemPrompt.length() > MAX_SYSTEM_PROMPT_LENGTH) {
            throw new IllegalArgumentException(
                    "systemPrompt must not exceed 50000 characters"
            );
        }

        Objects.requireNonNull(
                options,
                "options must not be null"
        );
        Objects.requireNonNull(
                messages,
                "messages must not be null"
        );
        Objects.requireNonNull(
                tools,
                "tools must not be null"
        );

        messages = List.copyOf(messages);
        tools = List.copyOf(tools);

        if (messages.isEmpty()) {
            throw new IllegalArgumentException(
                    "messages must not be empty"
            );
        }

        if (messages.size() > MAX_MESSAGES) {
            throw new IllegalArgumentException(
                    "messages must not contain more than 1000 entries"
            );
        }

        if (tools.size() > MAX_TOOLS) {
            throw new IllegalArgumentException(
                    "tools must not contain more than 64 entries"
            );
        }

        Set<String> toolNames = new HashSet<>();

        for (ChatToolDefinition tool : tools) {
            if (!toolNames.add(tool.name())) {
                throw new IllegalArgumentException(
                        "tool names must be unique"
                );
            }
        }
    }

    private static String normalizeModelName(String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "modelName must not be null"
            );
        }

        String normalized = value.trim();

        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    "modelName must not be blank"
            );
        }

        if (normalized.length() > MAX_MODEL_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "modelName must not exceed 128 characters"
            );
        }

        return normalized;
    }
}