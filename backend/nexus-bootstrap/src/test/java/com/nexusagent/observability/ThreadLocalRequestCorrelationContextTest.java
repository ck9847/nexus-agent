package com.nexusagent.observability;

import com.nexusagent.common.observability.RequestCorrelation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadLocalRequestCorrelationContextTest {

    private static final RequestCorrelation CORRELATION =
            new RequestCorrelation(
                    "req-1",
                    "trace-1",
                    "10.0.0.1"
            );

    @AfterEach
    void clearContext() {
        ThreadLocalRequestCorrelationContext.clear();
        ThreadLocalRequestCorrelationContext.clearMdc();
    }

    @Test
    void shouldSetAndRequireCorrelation() {
        ThreadLocalRequestCorrelationContext.set(CORRELATION);

        assertSame(
                CORRELATION,
                ThreadLocalRequestCorrelationContext.require()
        );
        assertSame(
                CORRELATION,
                ThreadLocalRequestCorrelationContext.currentOrNull()
        );
        assertTrue(
                ThreadLocalRequestCorrelationContext
                        .current()
                        .isPresent()
        );
        assertSame(
                CORRELATION,
                ThreadLocalRequestCorrelationContext
                        .current()
                        .orElseThrow()
        );

        ThreadLocalRequestCorrelationContext.clear();

        assertNull(ThreadLocalRequestCorrelationContext.currentOrNull());
        assertTrue(
                ThreadLocalRequestCorrelationContext
                        .current()
                        .isEmpty()
        );
        assertThrows(
                IllegalStateException.class,
                ThreadLocalRequestCorrelationContext::require
        );
    }

    @Test
    void shouldRejectNullCorrelation() {
        assertThrows(
                NullPointerException.class,
                () -> ThreadLocalRequestCorrelationContext.set(null)
        );
    }

    @Test
    void shouldIsolateCorrelationBetweenThreads() throws Exception {
        ThreadLocalRequestCorrelationContext.set(CORRELATION);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<RequestCorrelation> seen =
                new AtomicReference<>();
        AtomicReference<Throwable> failure =
                new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                assertNull(
                        ThreadLocalRequestCorrelationContext
                                .currentOrNull()
                );
                assertThrows(
                        IllegalStateException.class,
                        ThreadLocalRequestCorrelationContext::require
                );

                ThreadLocalRequestCorrelationContext.set(CORRELATION);
                seen.set(
                        ThreadLocalRequestCorrelationContext
                                .currentOrNull()
                );
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                ThreadLocalRequestCorrelationContext.clear();
                done.countDown();
            }
        });

        worker.start();

        assertTrue(
                done.await(5, TimeUnit.SECONDS),
                "worker did not finish in time"
        );
        assertNull(failure.get());

        // 主线程上下文不受 worker 影响。
        assertSame(
                CORRELATION,
                ThreadLocalRequestCorrelationContext.require()
        );
    }

    @Test
    void shouldAttachAndClearMdc() {
        ThreadLocalRequestCorrelationContext.attachMdc(CORRELATION);

        assertEquals(
                "req-1",
                MDC.get(
                        ThreadLocalRequestCorrelationContext
                                .MDC_REQUEST_ID
                )
        );
        assertEquals(
                "trace-1",
                MDC.get(
                        ThreadLocalRequestCorrelationContext
                                .MDC_TRACE_ID
                )
        );

        ThreadLocalRequestCorrelationContext.clearMdc();

        assertNull(MDC.get(
                ThreadLocalRequestCorrelationContext.MDC_REQUEST_ID
        ));
        assertNull(MDC.get(
                ThreadLocalRequestCorrelationContext.MDC_TRACE_ID
        ));
    }

    @Test
    void shouldPropagateCorrelationToDecoratedTask() throws Exception {
        try (Pool pool = new Pool()) {
            ThreadLocalRequestCorrelationContext.set(CORRELATION);
            ThreadLocalRequestCorrelationContext.attachMdc(CORRELATION);

            try {
                WorkerObservation observation = runAndObserve(pool);

                assertSame(CORRELATION, observation.correlation());
                assertEquals(
                        "req-1",
                        observation.requestIdFromMdc()
                );
                assertEquals(
                        "trace-1",
                        observation.traceIdFromMdc()
                );
            } finally {
                ThreadLocalRequestCorrelationContext.clear();
                ThreadLocalRequestCorrelationContext.clearMdc();
            }
        }
    }

    @Test
    void shouldNotLeakCorrelationAcrossReusedPoolThread()
            throws Exception {
        try (Pool pool = new Pool()) {
            // 第一个任务携带关联。
            ThreadLocalRequestCorrelationContext.set(CORRELATION);
            ThreadLocalRequestCorrelationContext.attachMdc(CORRELATION);

            try {
                WorkerObservation first = runAndObserve(pool);

                assertSame(CORRELATION, first.correlation());
                assertEquals(
                        "req-1",
                        first.requestIdFromMdc()
                );
            } finally {
                ThreadLocalRequestCorrelationContext.clear();
                ThreadLocalRequestCorrelationContext.clearMdc();
            }

            // 第二个任务提交时没有关联：同一池线程不得继承
            // 上一个请求的 ThreadLocal 或 MDC。
            WorkerObservation second = runAndObserve(pool);

            assertNull(second.correlation());
            assertNull(second.requestIdFromMdc());
            assertNull(second.traceIdFromMdc());
        }
    }

    @Test
    void shouldCleanupThreadLocalAndMdcAfterTaskFailure()
            throws Exception {
        try (Pool pool = new Pool()) {
            ThreadLocalRequestCorrelationContext.set(CORRELATION);
            ThreadLocalRequestCorrelationContext.attachMdc(CORRELATION);

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure =
                    new AtomicReference<>();

            pool.executor().execute(() -> {
                try {
                    throw new IllegalStateException("task boom");
                } catch (Throwable throwable) {
                    failure.set(throwable);
                } finally {
                    done.countDown();
                }
            });

            assertTrue(
                    done.await(5, TimeUnit.SECONDS),
                    "worker task did not finish in time"
            );
            assertEquals("task boom", failure.get().getMessage());

            // 观察任务必须在主线程上下文清理之后提交，
            // 否则 decorator 会重新把主线程上下文带给 worker。
            ThreadLocalRequestCorrelationContext.clear();
            ThreadLocalRequestCorrelationContext.clearMdc();

            WorkerObservation observation =
                    runAndObserve(pool);

            assertNull(observation.correlation());
            assertNull(observation.requestIdFromMdc());
            assertNull(observation.traceIdFromMdc());
        }
    }

    private static WorkerObservation runAndObserve(Pool pool)
            throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<RequestCorrelation> correlation =
                new AtomicReference<>();
        AtomicReference<String> requestIdFromMdc =
                new AtomicReference<>();
        AtomicReference<String> traceIdFromMdc =
                new AtomicReference<>();

        pool.executor().execute(() -> {
            correlation.set(
                    ThreadLocalRequestCorrelationContext
                            .currentOrNull()
            );
            requestIdFromMdc.set(MDC.get(
                    ThreadLocalRequestCorrelationContext
                            .MDC_REQUEST_ID
            ));
            traceIdFromMdc.set(MDC.get(
                    ThreadLocalRequestCorrelationContext
                            .MDC_TRACE_ID
            ));
            done.countDown();
        });

        assertTrue(
                done.await(5, TimeUnit.SECONDS),
                "worker task did not finish in time"
        );

        return new WorkerObservation(
                correlation.get(),
                requestIdFromMdc.get(),
                traceIdFromMdc.get()
        );
    }

    private static final class Pool implements AutoCloseable {

        private final ThreadPoolTaskExecutor executor;

        Pool() {
            executor = new ThreadPoolTaskExecutor();

            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(5);
            executor.setThreadNamePrefix("correlation-test-");
            executor.setTaskDecorator(
                    new RequestCorrelationTaskDecorator()
            );
            executor.afterPropertiesSet();
        }

        ThreadPoolTaskExecutor executor() {
            return executor;
        }

        @Override
        public void close() {
            executor.shutdown();
        }
    }

    private record WorkerObservation(
            RequestCorrelation correlation,
            String requestIdFromMdc,
            String traceIdFromMdc
    ) {
    }
}
