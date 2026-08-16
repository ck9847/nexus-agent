package com.nexusagent.conversation.api;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Future;

/**
 * 独立于 {@link ConversationController} 的 SSE 会话轮次端点。
 *
 * <p>提交必须发生在当前认证请求线程：executor 是
 * {@code DelegatingSecurityContextAsyncTaskExecutor}，只有从请求线程
 * submit 才能捕获到 JWT {@code SecurityContext} 并传播给 worker。
 *
 * <p>worker 失败时：若传输已失败则仅以
 * {@link SseEmitter#completeWithError} 结束连接；否则发送安全的
 * {@code error} 事件（映射自 {@link ConversationTurnSseErrors}）。
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationTurnStreamController {

    private final StreamConversationTurnService service;
    private final AsyncTaskExecutor executor;
    private final Duration timeout;
    private final ConversationTurnSseMetrics metrics;
    private final ConversationTurnRateLimiter rateLimiter;

    public ConversationTurnStreamController(
            StreamConversationTurnService service,
            @Qualifier("conversationTurnStreamExecutor")
            AsyncTaskExecutor executor,
            @Value("${nexus.conversation.streaming.timeout:2m}")
            Duration timeout,
            ConversationTurnSseMetrics metrics,
            ConversationTurnRateLimiter rateLimiter
    ) {
        this.service = Objects.requireNonNull(
                service,
                "service must not be null"
        );
        this.executor = Objects.requireNonNull(
                executor,
                "executor must not be null"
        );
        this.timeout = Objects.requireNonNull(
                timeout,
                "timeout must not be null"
        );
        this.metrics = Objects.requireNonNull(
                metrics,
                "metrics must not be null"
        );
        this.rateLimiter = Objects.requireNonNull(
                rateLimiter,
                "rateLimiter must not be null"
        );

        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException(
                    "timeout must be positive"
            );
        }
    }

    @PostMapping(
            value = "/{id}/turns:stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter stream(
            @PathVariable("id") String conversationId,
            @RequestHeader(
                    value = "Idempotency-Key",
                    required = false
            )
            String idempotencyKey,
            @Valid @RequestBody
            StreamConversationTurnRequest request
    ) {
        // 限流与幂等键校验都必须在提交线程池之前、当前认证
        // 请求线程上执行：过载时绝不占用 worker 名额，也不建立
        // SSE 连接；无效幂等键直接得到 400 而不是 SSE 错误事件。
        rateLimiter.checkPermission();
        String normalizedIdempotencyKey =
                normalizeIdempotencyKey(idempotencyKey);

        SseEmitter emitter =
                new SseEmitter(timeout.toMillis());

        ConversationTurnSseEventWriter writer =
                new ConversationTurnSseEventWriter(
                        emitter,
                        metrics
                );

        Future<?> future;
        try {
            future = executor.submit(() ->
                    runTurn(
                            conversationId,
                            request.content(),
                            normalizedIdempotencyKey,
                            emitter,
                            writer
                    )
            );
        } catch (TaskRejectedException rejection) {
            metrics.countCapacityRejected();
            throw new ConversationTurnCapacityExceededException(
                    rejection
            );
        }

        // submit 成功后才标记接受：即使 direct executor 让
        // worker 在 submit 返回前已结束，writer 也会先
        // established 再补结算 pending 终态，active 不泄漏。
        // reject 路径绝不会调用 markAccepted()。
        writer.markAccepted();

        emitter.onTimeout(() -> {
            writer.markTimeout();
            future.cancel(true);
        });

        emitter.onError(error -> {
            writer.markClientDisconnected();
            future.cancel(true);
        });

        // 正常完成的 worker 已通过 Completed 事件结束连接，
        // onCompletion 中不得中断它。
        emitter.onCompletion(() -> {
        });

        return emitter;
    }

    private void runTurn(
            String conversationId,
            String content,
            String idempotencyKey,
            SseEmitter emitter,
            ConversationTurnSseEventWriter writer
    ) {
        try {
            service.stream(
                    conversationId,
                    content,
                    idempotencyKey,
                    writer
            );
        } catch (RuntimeException failure) {
            handleWorkerFailure(
                    emitter,
                    writer,
                    failure
            );
        }
    }

    /**
     * worker 失败时的兜底：传输已失败只以
     * {@link SseEmitter#completeWithError} 结束连接；否则发送
     * 安全的 {@code error} 事件，error 事件自身发送失败时同样
     * 兜底结束连接，避免异常静默消失在 worker Future 中，
     * 也不会让 emitter 一直挂到超时。
     */
    void handleWorkerFailure(
            SseEmitter emitter,
            ConversationTurnSseEventWriter writer,
            RuntimeException failure
    ) {
        if (writer.transportFailed()) {
            completeWithErrorSafely(
                    emitter,
                    failure
            );
            return;
        }

        try {
            writer.sendError(
                    ConversationTurnSseErrors.from(failure),
                    failure
            );
        } catch (RuntimeException deliveryFailure) {
            writer.markTransportClosed();

            completeWithErrorSafely(
                    emitter,
                    deliveryFailure
            );
        }
    }

    /**
     * 归一化客户端幂等键：空白视为未提供；长度与注册命令
     * 的约束一致（128），超长键按无效请求拒绝。
     */
    private static String normalizeIdempotencyKey(
            String idempotencyKey
    ) {
        if (idempotencyKey == null) {
            return null;
        }

        String normalized = idempotencyKey.trim();

        if (normalized.isBlank()) {
            return null;
        }

        if (normalized.length() > 128) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must not exceed "
                            + "128 characters"
            );
        }

        return normalized;
    }

    private static void completeWithErrorSafely(
            SseEmitter emitter,
            Throwable failure
    ) {
        try {
            emitter.completeWithError(failure);
        } catch (RuntimeException ignored) {
            // Transport is already unusable.
        }
    }
}
