package com.nexusagent.conversation.api;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Objects;

/**
 * 将会话轮次流事件写入 {@link SseEmitter} 的 SSE 事件。
 *
 * <p>事件名固定：{@code Started} -> {@code started}、
 * {@code TextDelta} -> {@code delta}、{@code Completed} -> {@code completed}，
 * 失败通过 {@link #sendError} 以 {@code error} 发送。
 *
 * <p>所有状态变更在同一个监视器下同步，避免并发发送
 * 破坏 {@code transportFailed}/{@code terminal} 语义。
 * 一旦传输失败，后续不再尝试向客户端发送任何事件。
 *
 * <p>连接指标（{@link ConversationTurnSseMetrics}）由
 * {@code metricsEnded} 保证每个连接只结算一次：
 * {@code onTimeout} 与 {@code onError} 同时触发、或 error 事件
 * 自身发送失败等叠加场景，都只会 decrement 一次 active 并只
 * 计数一种终止方式。
 */
public class ConversationTurnSseEventWriter
        implements ConversationTurnStreamHandler {

    private final SseEmitter emitter;
    private final ConversationTurnSseMetrics metrics;
    private final Object monitor = new Object();

    private boolean completed;
    private boolean failed;
    private boolean transportFailed;
    private boolean metricsStarted;
    private boolean metricsEnded;
    private ConversationTurnSseMetrics.End pendingMetricsEnd;

    public ConversationTurnSseEventWriter(SseEmitter emitter) {
        this(emitter, null);
    }

    public ConversationTurnSseEventWriter(
            SseEmitter emitter,
            ConversationTurnSseMetrics metrics
    ) {
        this.emitter = Objects.requireNonNull(
                emitter,
                "emitter must not be null"
        );
        this.metrics = metrics;
    }

    @Override
    public void onEvent(ConversationTurnStreamEvent event) {
        Objects.requireNonNull(
                event,
                "event must not be null"
        );

        if (event instanceof ConversationTurnStreamEvent.Started) {
            send("started", event);
            return;
        }
        if (event instanceof ConversationTurnStreamEvent.TextDelta) {
            send("delta", event);
            return;
        }
        if (event instanceof ConversationTurnStreamEvent.Completed) {
            send("completed", event);
            return;
        }
        throw new IllegalArgumentException(
                "Unsupported conversation turn stream event"
        );
    }

    /**
     * 以 {@code error} 事件向客户端发送失败信息。
     *
     * <p>传输已失败或连接已终结时不再尝试发送；
     * {@link Throwable} 仅用于调用方，绝不会写入 SSE 负载。
     */
    void sendError(
            ConversationTurnSseError error,
            Throwable failure
    ) {
        Objects.requireNonNull(
                error,
                "error must not be null"
        );
        Objects.requireNonNull(
                failure,
                "failure must not be null"
        );

        synchronized (monitor) {
            if (completed || failed || transportFailed) {
                return;
            }

            try {
                sendLocked("error", error);
            } catch (ConversationTurnStreamDeliveryException
                    deliveryFailure) {
                // error 事件自身发送失败：单独计数；
                // 连接以客户端断连结束（sendLocked 已置
                // transportFailed=true）。
                countErrorSendFailure();
                endMetrics(
                        ConversationTurnSseMetrics.End
                                .CLIENT_DISCONNECT
                );
                throw deliveryFailure;
            }

            failed = true;
            endMetrics(ConversationTurnSseMetrics.End.ERROR);
            completeLocked();
        }
    }

    /**
     * 记录底层传输已关闭（兼容既有调用点），
     * 语义等同客户端断连。
     */
    void markTransportClosed() {
        markClientDisconnected();
    }

    /**
     * {@code SseEmitter.onTimeout} 回调：连接超时结束。
     */
    void markTimeout() {
        synchronized (monitor) {
            transportFailed = true;
            endMetrics(ConversationTurnSseMetrics.End.TIMEOUT);
        }
    }

    /**
     * {@code SseEmitter.onError} 回调：客户端断连结束。
     */
    void markClientDisconnected() {
        synchronized (monitor) {
            transportFailed = true;
            endMetrics(
                    ConversationTurnSseMetrics.End.CLIENT_DISCONNECT
            );
        }
    }

    /**
     * Controller 在 executor {@code submit()} 成功返回后调用：
     * 连接被正式接受，开始建立指标。
     *
     * <p>若 worker 已经运行完毕并提前结算了终态
     * （{@link #endMetrics} 发生在本方法之前），这里在
     * established 之后立即补结算，保证 active 不泄漏。
     */
    void markAccepted() {
        synchronized (monitor) {
            if (metricsStarted) {
                return;
            }

            metricsStarted = true;

            if (metrics != null) {
                metrics.connectionEstablished();
            }

            if (metricsEnded) {
                endMetricsNow(pendingMetricsEnd);
            }
        }
    }

    boolean transportFailed() {
        synchronized (monitor) {
            return transportFailed;
        }
    }

    boolean terminal() {
        synchronized (monitor) {
            return completed || failed || transportFailed;
        }
    }

    @Override
    public boolean isCancellationRequested() {
        return transportFailed();
    }

    private void send(
            String eventName,
            ConversationTurnStreamEvent event
    ) {
        synchronized (monitor) {
            if (transportFailed) {
                throw new ConversationTurnStreamDeliveryException(
                        new IllegalStateException(
                                "SSE transport already failed"
                        )
                );
            }

            try {
                sendLocked(eventName, event);
            } catch (ConversationTurnStreamDeliveryException
                    deliveryFailure) {
                // sendLocked 已置 transportFailed=true：
                // 传输失败即客户端断连。
                endMetrics(
                        ConversationTurnSseMetrics.End
                                .CLIENT_DISCONNECT
                );
                throw deliveryFailure;
            }

            if (event instanceof ConversationTurnStreamEvent.Completed) {
                completed = true;
                endMetrics(ConversationTurnSseMetrics.End.COMPLETED);
                completeLocked();
            }
        }
    }

    /**
     * 每个连接只结算一次：任何后续终止（onTimeout/onError
     * 叠加、重复 error 等）都不会再计数或 decrement。
     *
     * <p>若结算发生在 {@link #markAccepted()} 之前，只保存
     * 终态（pendingMetricsEnd），等 accepted 时补结算，
     * 避免“先 end 钳制到 0、后 established 永久泄漏”的竞态。
     */
    private void endMetrics(ConversationTurnSseMetrics.End end) {
        if (metricsEnded) {
            return;
        }

        metricsEnded = true;
        pendingMetricsEnd = end;

        if (metricsStarted) {
            endMetricsNow(end);
        }
    }

    private void endMetricsNow(ConversationTurnSseMetrics.End end) {
        if (metrics != null) {
            metrics.connectionEnded(end);
        }
    }

    private void countErrorSendFailure() {
        if (metrics != null) {
            metrics.countErrorSendFailure();
        }
    }

    private void sendLocked(
            String eventName,
            Object payload
    ) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name(eventName)
                            .data(payload, MediaType.APPLICATION_JSON)
            );
        } catch (IOException | RuntimeException cause) {
            transportFailed = true;
            throw new ConversationTurnStreamDeliveryException(cause);
        }
    }

    private void completeLocked() {
        try {
            emitter.complete();
        } catch (RuntimeException cause) {
            transportFailed = true;
            throw new ConversationTurnStreamDeliveryException(cause);
        }
    }
}

/**
 * 底层 SSE 传输交付失败时抛出的运行时异常，文案固定。
 */
final class ConversationTurnStreamDeliveryException
        extends RuntimeException {

    ConversationTurnStreamDeliveryException(Throwable cause) {
        super("Conversation turn SSE delivery failed", cause);
    }
}
