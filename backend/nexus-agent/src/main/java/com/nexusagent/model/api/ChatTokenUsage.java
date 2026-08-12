package com.nexusagent.model.api;

public record ChatTokenUsage(
        int promptTokens,
        int completionTokens
) {

    public ChatTokenUsage {
        if (promptTokens < 0) {
            throw new IllegalArgumentException(
                    "promptTokens must not be negative"
            );
        }

        if (completionTokens < 0) {
            throw new IllegalArgumentException(
                    "completionTokens must not be negative"
            );
        }
    }

    public long totalTokens() {
        return (long) promptTokens + completionTokens;
    }
}