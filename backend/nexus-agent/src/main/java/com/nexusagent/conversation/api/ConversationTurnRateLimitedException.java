package com.nexusagent.conversation.api;

import java.time.Duration;
import java.util.Objects;

/**
 * 会话轮次被 tenant/user 维度限流拒绝。
 *
 * <p>映射为 HTTP 429 + {@code Retry-After}（秒）。
 */
public final class ConversationTurnRateLimitedException
        extends RuntimeException {

    private final Duration retryAfter;

    public ConversationTurnRateLimitedException(
            Duration retryAfter
    ) {
        super("Conversation turn rate limit exceeded");

        this.retryAfter = Objects.requireNonNull(
                retryAfter,
                "retryAfter must not be null"
        );

        if (retryAfter.isNegative()
                || retryAfter.isZero()) {
            throw new IllegalArgumentException(
                    "retryAfter must be positive"
            );
        }
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
