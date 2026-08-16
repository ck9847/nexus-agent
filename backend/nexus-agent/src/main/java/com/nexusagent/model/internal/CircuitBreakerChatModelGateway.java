package com.nexusagent.model.internal;

import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelGateway;
import com.nexusagent.model.api.ChatModelRequest;
import com.nexusagent.model.api.ChatModelStreamHandler;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

import java.util.Objects;

/**
 * 用供应商维度熔断器装饰 {@link ChatModelGateway}。
 *
 * <p>熔断器 OPEN 时不再发起真实供应商调用，立即以
 * {@link ChatModelErrorCategory#CIRCUIT_OPEN}（不可重试）
 * 快速失败——上游会得到明确的错误分类，客户端与指标都能
 * 区分“供应商故障”与“熔断保护中”。
 *
 * <p>哪些异常计入熔断由注册表的默认配置决定
 * （见 ResilienceConfiguration）：客户端断开与流外中断被忽略。
 */
public final class CircuitBreakerChatModelGateway
        implements ChatModelGateway {

    private final ChatModelGateway delegate;

    private final CircuitBreaker circuitBreaker;

    public CircuitBreakerChatModelGateway(
            ChatModelGateway delegate,
            CircuitBreaker circuitBreaker
    ) {
        this.delegate = Objects.requireNonNull(
                delegate,
                "delegate must not be null"
        );
        this.circuitBreaker = Objects.requireNonNull(
                circuitBreaker,
                "circuitBreaker must not be null"
        );
    }

    @Override
    public AgentModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public void stream(
            ChatModelRequest request,
            ChatModelStreamHandler handler
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );
        Objects.requireNonNull(
                handler,
                "handler must not be null"
        );

        try {
            circuitBreaker.executeRunnable(() ->
                    delegate.stream(request, handler)
            );
        } catch (CallNotPermittedException rejected) {
            throw new ChatModelException(
                    ChatModelErrorCategory.CIRCUIT_OPEN,
                    "Chat model provider circuit "
                            + "breaker is open",
                    null,
                    rejected
            );
        }
    }
}
