package com.nexusagent.conversation.internal;

import com.nexusagent.conversation.api.ConversationTurnStreamEvent;
import com.nexusagent.conversation.api.ConversationTurnStreamHandler;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 记录“是否已经向客户端转发过任何事件”的 handler 包装。
 *
 * <p>安全重试的判定边界：只要本包装观察到一次
 * {@code onEvent}，说明客户端已经收到（至少部分）内容，
 * 此时重放模型调用必然导致内容重复——重试必须被禁止。
 *
 * <p>每次重试尝试使用一个全新的包装实例，边界语义是
 * “本次尝试尚未转发任何事件”，跨尝试互不污染。
 */
final class ForwardingGuardStreamHandler
        implements ConversationTurnStreamHandler {

    private final ConversationTurnStreamHandler delegate;

    private final AtomicBoolean forwarded =
            new AtomicBoolean(false);

    ForwardingGuardStreamHandler(
            ConversationTurnStreamHandler delegate
    ) {
        this.delegate = Objects.requireNonNull(
                delegate,
                "delegate must not be null"
        );
    }

    @Override
    public void onEvent(
            ConversationTurnStreamEvent event
    ) {
        forwarded.set(true);

        delegate.onEvent(event);
    }

    @Override
    public boolean isCancellationRequested() {
        return delegate.isCancellationRequested();
    }

    boolean hasForwardedModelEvent() {
        return forwarded.get();
    }
}
