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
 */
public class ConversationTurnSseEventWriter
        implements ConversationTurnStreamHandler {

    private final SseEmitter emitter;
    private final Object monitor = new Object();

    private boolean completed;
    private boolean failed;
    private boolean transportFailed;

    public ConversationTurnSseEventWriter(SseEmitter emitter) {
        this.emitter = Objects.requireNonNull(
                emitter,
                "emitter must not be null"
        );
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
            sendLocked("error", error);
            failed = true;
            completeLocked();
        }
    }

    /**
     * 记录底层传输已关闭，禁止任何后续发送。
     */
    void markTransportClosed() {
        synchronized (monitor) {
            transportFailed = true;
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
            sendLocked(eventName, event);
            if (event instanceof ConversationTurnStreamEvent.Completed) {
                completed = true;
                completeLocked();
            }
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
