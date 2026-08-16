package com.nexusagent.conversation.api;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE 连接生命周期指标。
 *
 * <ul>
 *     <li>Gauge：{@value #ACTIVE_GAUGE}（当前活跃连接数）；</li>
 *     <li>计数器：已建立、正常完成、error 事件结束、
 *         client disconnect、timeout、executor 拒绝、
 *         error 事件发送失败、限流拒绝。</li>
 * </ul>
 *
 * <p>关键不变量：
 * <ul>
 *     <li>active gauge 钳制递减，永远不小于 0；</li>
 *     <li>所有方法内部吞掉 {@link RuntimeException}：
 *         指标异常绝不能影响 SSE 主流程（含构造期注册失败）。</li>
 * </ul>
 */
@Component
public final class ConversationTurnSseMetrics {

    public static final String ACTIVE_GAUGE =
            "nexus.sse.connections.active";
    public static final String ESTABLISHED_COUNTER =
            "nexus.sse.connections.established";
    public static final String COMPLETED_COUNTER =
            "nexus.sse.connections.completed";
    public static final String ERROR_COUNTER =
            "nexus.sse.connections.error";
    public static final String CLIENT_DISCONNECT_COUNTER =
            "nexus.sse.connections.client_disconnect";
    public static final String TIMEOUT_COUNTER =
            "nexus.sse.connections.timeout";
    public static final String CAPACITY_REJECTED_COUNTER =
            "nexus.sse.connections.capacity_rejected";
    public static final String ERROR_SEND_FAILURE_COUNTER =
            "nexus.sse.connections.error_send_failure";
    public static final String RATE_LIMITED_COUNTER =
            "nexus.sse.connections.rate_limited";

    /**
     * 连接结束方式，决定终止计数落在哪个计数器上。
     */
    public enum End {
        COMPLETED,
        ERROR,
        CLIENT_DISCONNECT,
        TIMEOUT
    }

    private final AtomicInteger active = new AtomicInteger();

    private final Counter established;
    private final Counter completed;
    private final Counter error;
    private final Counter clientDisconnect;
    private final Counter timeout;
    private final Counter capacityRejected;
    private final Counter errorSendFailure;
    private final Counter rateLimited;

    public ConversationTurnSseMetrics(MeterRegistry registry) {
        Counter established = null;
        Counter completed = null;
        Counter error = null;
        Counter clientDisconnect = null;
        Counter timeout = null;
        Counter capacityRejected = null;
        Counter errorSendFailure = null;
        Counter rateLimited = null;

        try {
            Gauge.builder(
                    ACTIVE_GAUGE,
                    active,
                    AtomicInteger::doubleValue
            ).register(registry);

            established = registry.counter(
                    ESTABLISHED_COUNTER
            );
            completed = registry.counter(
                    COMPLETED_COUNTER
            );
            error = registry.counter(ERROR_COUNTER);
            clientDisconnect = registry.counter(
                    CLIENT_DISCONNECT_COUNTER
            );
            timeout = registry.counter(TIMEOUT_COUNTER);
            capacityRejected = registry.counter(
                    CAPACITY_REJECTED_COUNTER
            );
            errorSendFailure = registry.counter(
                    ERROR_SEND_FAILURE_COUNTER
            );
            rateLimited = registry.counter(
                    RATE_LIMITED_COUNTER
            );
        } catch (RuntimeException ignored) {
            // 指标初始化失败绝不能影响 SSE 主流程：
            // 全部计数退化为 no-op。
        }

        this.established = established;
        this.completed = completed;
        this.error = error;
        this.clientDisconnect = clientDisconnect;
        this.timeout = timeout;
        this.capacityRejected = capacityRejected;
        this.errorSendFailure = errorSendFailure;
        this.rateLimited = rateLimited;
    }

    /**
     * 当前活跃连接数。钳制递减保证永远不小于 0。
     */
    public int activeConnections() {
        return active.get();
    }

    public void connectionEstablished() {
        try {
            active.incrementAndGet();
        } catch (RuntimeException ignored) {
        }

        increment(established);
    }

    /**
     * 连接结束：active 恰递减一次（钳制），并按结束方式计数。
     *
     * <p>“每个连接只 decrement 一次”由调用方
     * （{@link ConversationTurnSseEventWriter}）的
     * {@code metricsEnded} 状态保证；这里的钳制是纵深防御。
     */
    public void connectionEnded(End end) {
        try {
            active.updateAndGet(
                    value -> value > 0 ? value - 1 : 0
            );
        } catch (RuntimeException ignored) {
        }

        increment(counterFor(end));
    }

    public void countCapacityRejected() {
        increment(capacityRejected);
    }

    public void countErrorSendFailure() {
        increment(errorSendFailure);
    }

    public void countRateLimited() {
        increment(rateLimited);
    }

    private Counter counterFor(End end) {
        return switch (end) {
            case COMPLETED -> completed;
            case ERROR -> error;
            case CLIENT_DISCONNECT -> clientDisconnect;
            case TIMEOUT -> timeout;
        };
    }

    private static void increment(Counter counter) {
        if (counter == null) {
            return;
        }

        try {
            counter.increment();
        } catch (RuntimeException ignored) {
            // 指标异常绝不能影响 SSE 主流程。
        }
    }
}
