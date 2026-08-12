package com.nexusagent.model.api;

public enum ChatModelErrorCategory {

    AUTHENTICATION(false),
    INVALID_REQUEST(false),
    CONTENT_FILTERED(false),
    RATE_LIMIT(true),
    TIMEOUT(true),
    CONNECTION(true),
    PROVIDER_UNAVAILABLE(true),
    MALFORMED_RESPONSE(false),
    STREAM_INTERRUPTED(true);

    private final boolean retryable;

    ChatModelErrorCategory(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}