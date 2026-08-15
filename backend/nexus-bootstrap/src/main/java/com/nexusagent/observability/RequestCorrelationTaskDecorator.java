package com.nexusagent.observability;

import com.nexusagent.common.observability.RequestCorrelation;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * 把提交线程的请求关联上下文传播给线程池 worker。
 *
 * <p>{@code decorate} 在提交线程调用并同步捕获当前关联
 * （requestId、traceId、ipAddress）；包装后的任务在 worker 线程
 * 开始执行前恢复 ThreadLocal 与 SLF4J MDC，执行结束后恢复
 * worker 原有值——原本没有上下文则清理为 null，原本已有上下文
 * 则原样恢复，保证池线程复用时既不泄漏、也不破坏嵌套执行中
 * worker 已有的关联。
 *
 * <p>提交线程没有关联时（{@code captured == null}），进入任务前
 * 必须完整覆盖 worker 状态：显式清除 ThreadLocal 与 MDC。否则
 * 意外残留旧上下文的池线程会把上一个请求的 requestId、traceId、
 * clientIp 与 MDC 日志字段泄漏给"无上下文任务"，造成跨请求、
 * 跨租户的审计关联错误。
 */
public class RequestCorrelationTaskDecorator
        implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        RequestCorrelation captured =
                ThreadLocalRequestCorrelationContext.currentOrNull();

        return () -> {
            RequestCorrelation previous =
                    ThreadLocalRequestCorrelationContext
                            .currentOrNull();
            String previousRequestId = MDC.get(
                    ThreadLocalRequestCorrelationContext
                            .MDC_REQUEST_ID
            );
            String previousTraceId = MDC.get(
                    ThreadLocalRequestCorrelationContext
                            .MDC_TRACE_ID
            );

            if (captured != null) {
                ThreadLocalRequestCorrelationContext.set(captured);
                ThreadLocalRequestCorrelationContext
                        .attachMdc(captured);
            } else {
                // 无关联上下文任务：必须完整覆盖 worker 状态，
                // 防止残留的上一个请求关联泄漏进任务。
                ThreadLocalRequestCorrelationContext.clear();
                ThreadLocalRequestCorrelationContext.clearMdc();
            }

            try {
                runnable.run();
            } finally {
                restore(
                        previous,
                        previousRequestId,
                        previousTraceId
                );
            }
        };
    }

    private static void restore(
            RequestCorrelation previous,
            String previousRequestId,
            String previousTraceId
    ) {
        if (previous != null) {
            ThreadLocalRequestCorrelationContext.set(previous);
        } else {
            ThreadLocalRequestCorrelationContext.clear();
        }

        restoreMdcKey(
                ThreadLocalRequestCorrelationContext
                        .MDC_REQUEST_ID,
                previousRequestId
        );
        restoreMdcKey(
                ThreadLocalRequestCorrelationContext
                        .MDC_TRACE_ID,
                previousTraceId
        );
    }

    private static void restoreMdcKey(
            String key,
            String value
    ) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
