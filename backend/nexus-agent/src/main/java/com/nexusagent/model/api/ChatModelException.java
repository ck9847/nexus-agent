package com.nexusagent.model.api;

import java.util.Objects;

public final class ChatModelException
        extends RuntimeException {

    private final ChatModelErrorCategory category;
    private final Integer httpStatus;

    public ChatModelException(
            ChatModelErrorCategory category,
            String safeMessage
    ) {
        this(category, safeMessage, null, null);
    }

    public ChatModelException(
            ChatModelErrorCategory category,
            String safeMessage,
            Integer httpStatus,
            Throwable cause
    ) {
        super(requireSafeMessage(safeMessage), cause);

        this.category = Objects.requireNonNull(
                category,
                "category must not be null"
        );

        if (httpStatus != null
                && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException(
                    "httpStatus must be between 100 and 599"
            );
        }

        this.httpStatus = httpStatus;
    }

    public ChatModelErrorCategory category() {
        return category;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return category.retryable();
    }

    private static String requireSafeMessage(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "safeMessage must not be blank"
            );
        }

        return value;
    }
}