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
    STREAM_INTERRUPTED(true),

    /**
     * 供应商熔断器处于 OPEN 状态时的快速失败。
     *
     * <p>这不是一次真实的供应商调用，因此不可重试：
     * 在等待窗口结束前重试只会再次被拒绝。
     */
    CIRCUIT_OPEN(false);

    private final boolean retryable;

    ChatModelErrorCategory(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}