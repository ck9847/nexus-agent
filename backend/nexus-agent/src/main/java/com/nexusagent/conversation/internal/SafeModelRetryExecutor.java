package com.nexusagent.conversation.internal;

import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.conversation.api.ConversationTurnStreamHandler;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelStreamConsumerException;
import com.nexusagent.resilience.ResilienceProperties;
import io.github.resilience4j.core.IntervalFunction;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 模型调用的安全重试执行器。
 *
 * <p>只有同时满足以下条件才重试：
 * <ul>
 *     <li>异常是 {@link ChatModelException} 且
 *         {@link ChatModelException#retryable()} 为真；</li>
 *     <li><b>尚未向客户端转发任何模型事件</b>
 *         （由 {@link ForwardingGuardStreamHandler} 判定）——
 *         重放已经输出过内容的流会导致内容重复，绝不重试；</li>
 *     <li>尝试次数未达 {@code nexus.resilience.model-retry.max-attempts}。</li>
 * </ul>
 *
 * <p>消费侧失败（{@link ChatModelStreamConsumerException}，
 * 客户端已断开）绝不重试。重试之间的退避由
 * {@link IntervalFunction}（指数退避）提供。
 *
 * <p>指标：每次重试、重试后成功、重试耗尽、因已转发事件而
 * 放弃重试，分别记入 {@code nexus.model.retry}。
 */
@Component
public final class SafeModelRetryExecutor {

    /**
     * 一次以 guarded handler 为下游的模型调用尝试。
     *
     * @param <T> 该轮模型调用的聚合结果类型
     */
    @FunctionalInterface
    public interface ModelAttempt<T> {

        T run(ConversationTurnStreamHandler guarded);
    }

    private final ResilienceProperties properties;

    private final IntervalFunction backoff;

    private final ConversationTurnMetrics metrics;

    public SafeModelRetryExecutor(
            ResilienceProperties properties,
            IntervalFunction backoff,
            ConversationTurnMetrics metrics
    ) {
        this.properties = Objects.requireNonNull(
                properties,
                "properties must not be null"
        );
        this.backoff = Objects.requireNonNull(
                backoff,
                "backoff must not be null"
        );
        this.metrics = Objects.requireNonNull(
                metrics,
                "metrics must not be null"
        );
    }

    public <T> T execute(
            AgentModelProvider provider,
            ConversationTurnStreamHandler downstream,
            ModelAttempt<T> attempt
    ) {
        Objects.requireNonNull(
                provider,
                "provider must not be null"
        );
        Objects.requireNonNull(
                downstream,
                "downstream must not be null"
        );
        Objects.requireNonNull(
                attempt,
                "attempt must not be null"
        );

        int maxAttempts =
                properties.modelRetry().maxAttempts();

        for (int attemptNo = 1; ; attemptNo++) {
            ForwardingGuardStreamHandler guard =
                    new ForwardingGuardStreamHandler(
                            downstream
                    );

            try {
                T result = attempt.run(guard);

                if (attemptNo > 1) {
                    metrics.incrementModelRetry(
                            provider,
                            ConversationTurnMetrics
                                    .RETRY_OUTCOME_SUCCEEDED
                    );
                }

                return result;
            } catch (ChatModelStreamConsumerException failure) {
                // 客户端已经断开：重试没有接收方。
                throw failure;
            } catch (ChatModelException failure) {
                boolean lastAttempt =
                        attemptNo >= maxAttempts;

                if (lastAttempt
                        || !failure.retryable()
                        || guard.hasForwardedModelEvent()) {
                    if (attemptNo > 1) {
                        metrics.incrementModelRetry(
                                provider,
                                ConversationTurnMetrics
                                        .RETRY_OUTCOME_EXHAUSTED
                        );
                    } else if (guard.hasForwardedModelEvent()) {
                        metrics.incrementModelRetry(
                                provider,
                                ConversationTurnMetrics
                                        .RETRY_OUTCOME_BLOCKED
                        );
                    }

                    throw failure;
                }

                metrics.incrementModelRetry(
                        provider,
                        ConversationTurnMetrics
                                .RETRY_OUTCOME_ATTEMPTED
                );

                sleepBeforeRetry(attemptNo);
            }
        }
    }

    private void sleepBeforeRetry(int attemptNo) {
        long delayMillis;

        try {
            delayMillis = backoff.apply(attemptNo - 1);
        } catch (RuntimeException invalidInterval) {
            // 退避函数异常：立即重试优于放弃整个 turn。
            return;
        }

        if (delayMillis <= 0) {
            return;
        }

        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();

            throw new ChatModelException(
                    com.nexusagent.model.api
                            .ChatModelErrorCategory
                            .STREAM_INTERRUPTED,
                    "Model call retry was interrupted",
                    null,
                    interrupted
            );
        }
    }
}
