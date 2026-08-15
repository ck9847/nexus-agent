package com.nexusagent.observability;

import com.nexusagent.common.observability.RequestCorrelation;
import org.slf4j.MDC;

import java.util.Objects;
import java.util.Optional;

/**
 * 以 ThreadLocal 持有当前请求的关联上下文。
 *
 * <p>{@link RequestCorrelationFilter} 在请求开始时写入、结束时
 * 清理；{@link RequestCorrelationTaskDecorator} 负责把提交线程的
 * 上下文复制到异步 worker，任务结束后清理，确保线程复用时
 * 不会泄漏上一个请求的关联信息。
 */
public final class ThreadLocalRequestCorrelationContext {

    public static final String MDC_REQUEST_ID = "requestId";

    public static final String MDC_TRACE_ID = "traceId";

    private static final ThreadLocal<RequestCorrelation> HOLDER =
            new ThreadLocal<>();

    private ThreadLocalRequestCorrelationContext() {
    }

    public static void set(RequestCorrelation correlation) {
        HOLDER.set(Objects.requireNonNull(
                correlation,
                "correlation must not be null"
        ));
    }

    public static RequestCorrelation require() {
        return current().orElseThrow(() ->
                new IllegalStateException(
                        "No request correlation context "
                                + "on the current thread"
                )
        );
    }

    /**
     * 以 {@link Optional} 表达当前关联：{@link Optional#empty()}
     * 表示当前线程确实没有请求关联上下文。
     */
    public static Optional<RequestCorrelation> current() {
        return Optional.ofNullable(HOLDER.get());
    }

    public static RequestCorrelation currentOrNull() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 把关联上下文写入当前线程的 SLF4J MDC。
     */
    public static void attachMdc(RequestCorrelation correlation) {
        Objects.requireNonNull(
                correlation,
                "correlation must not be null"
        );

        MDC.put(MDC_REQUEST_ID, correlation.requestId());
        MDC.put(MDC_TRACE_ID, correlation.traceId());
    }

    /**
     * 无条件清理当前线程的关联 MDC 键。
     */
    public static void clearMdc() {
        MDC.remove(MDC_REQUEST_ID);
        MDC.remove(MDC_TRACE_ID);
    }
}
