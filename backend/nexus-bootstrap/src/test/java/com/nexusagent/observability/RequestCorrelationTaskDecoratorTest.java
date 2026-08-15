package com.nexusagent.observability;

import com.nexusagent.common.observability.RequestCorrelation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯单元验证 {@link RequestCorrelationTaskDecorator} 的传播语义：
 * 提交线程同步捕获快照（requestId/traceId/ipAddress + MDC）、
 * worker 执行前恢复、执行后还原 worker 原有值或清理为 null。
 */
class RequestCorrelationTaskDecoratorTest {

    private static final RequestCorrelation CORRELATION_A =
            new RequestCorrelation(
                    "req-a",
                    "trace-a",
                    "10.0.0.1"
            );

    private static final RequestCorrelation CORRELATION_B =
            new RequestCorrelation(
                    "req-b",
                    "trace-b",
                    "10.0.0.2"
            );

    @AfterEach
    void clearContext() {
        ThreadLocalRequestCorrelationContext.clear();
        ThreadLocalRequestCorrelationContext.clearMdc();
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldPropagateCorrelationFromSubmitThreadToWorker()
            throws Exception {
        try (Pool pool = new Pool()) {
            ThreadLocalRequestCorrelationContext.set(CORRELATION_A);
            ThreadLocalRequestCorrelationContext.attachMdc(
                    CORRELATION_A
            );

            try {
                Observation seen = observe(pool.worker());

                // record 相等覆盖 requestId/traceId/ipAddress。
                assertEquals(CORRELATION_A, seen.correlation());
                assertEquals(
                        "req-a",
                        seen.requestIdFromMdc()
                );
                assertEquals(
                        "trace-a",
                        seen.traceIdFromMdc()
                );
            } finally {
                ThreadLocalRequestCorrelationContext.clear();
                ThreadLocalRequestCorrelationContext.clearMdc();
            }
        }
    }

    @Test
    void shouldCleanupAfterWorkerFailure() throws Exception {
        try (Pool pool = new Pool()) {
            ThreadLocalRequestCorrelationContext.set(CORRELATION_A);
            ThreadLocalRequestCorrelationContext.attachMdc(
                    CORRELATION_A
            );

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure =
                    new AtomicReference<>();

            pool.worker().execute(() -> {
                try {
                    throw new IllegalStateException(
                            "worker boom"
                    );
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
            assertEquals(
                    "worker boom",
                    failure.get().getMessage()
            );

            // 提交线程清理后，观察任务验证 worker 无残留。
            ThreadLocalRequestCorrelationContext.clear();
            ThreadLocalRequestCorrelationContext.clearMdc();

            Observation seen = observe(pool.worker());

            assertNull(seen.correlation());
            assertNull(seen.requestIdFromMdc());
            assertNull(seen.traceIdFromMdc());
        }
    }

    @Test
    void shouldNotMixCorrelationsAcrossConsecutiveTasks()
            throws Exception {
        try (Pool pool = new Pool()) {
            ThreadLocalRequestCorrelationContext.set(CORRELATION_A);
            ThreadLocalRequestCorrelationContext.attachMdc(
                    CORRELATION_A
            );

            ThreadObservation first;
            ThreadObservation second;

            try {
                first = observeWithThreadName(pool.worker());
            } finally {
                ThreadLocalRequestCorrelationContext.clear();
                ThreadLocalRequestCorrelationContext.clearMdc();
            }

            assertEquals(CORRELATION_A, first.observation().correlation());

            ThreadLocalRequestCorrelationContext.set(CORRELATION_B);
            ThreadLocalRequestCorrelationContext.attachMdc(
                    CORRELATION_B
            );

            try {
                second = observeWithThreadName(pool.worker());
            } finally {
                ThreadLocalRequestCorrelationContext.clear();
                ThreadLocalRequestCorrelationContext.clearMdc();
            }

            assertEquals(CORRELATION_B, second.observation().correlation());
            assertNotEquals(
                    CORRELATION_A,
                    second.observation().correlation()
            );

            // core=1：两个任务由同一池线程执行，各自只能看到
            // 自己提交时的快照。
            assertEquals(
                    first.threadName(),
                    second.threadName()
            );
        }
    }

    @Test
    void shouldPropagateSecurityContextAndCorrelationTogether()
            throws Exception {
        try (Pool pool = new Pool()) {
            setAuthentication("correlation-user");

            ThreadLocalRequestCorrelationContext.set(CORRELATION_A);
            ThreadLocalRequestCorrelationContext.attachMdc(
                    CORRELATION_A
            );

            try {
                CountDownLatch done = new CountDownLatch(1);
                AtomicReference<Authentication> seenAuth =
                        new AtomicReference<>();
                AtomicReference<RequestCorrelation> seenCorrelation =
                        new AtomicReference<>();

                pool.stream().execute(() -> {
                    seenAuth.set(
                            SecurityContextHolder
                                    .getContext()
                                    .getAuthentication()
                    );
                    seenCorrelation.set(
                            ThreadLocalRequestCorrelationContext
                                    .currentOrNull()
                    );
                    done.countDown();
                });

                assertTrue(
                        done.await(5, TimeUnit.SECONDS),
                        "worker task did not finish in time"
                );

                Authentication authentication = seenAuth.get();

                assertNotNull(authentication);
                assertEquals(
                        "correlation-user",
                        authentication.getName()
                );
                assertEquals(
                        CORRELATION_A,
                        seenCorrelation.get()
                );
            } finally {
                ThreadLocalRequestCorrelationContext.clear();
                ThreadLocalRequestCorrelationContext.clearMdc();
                SecurityContextHolder.clearContext();
            }
        }
    }

    @Test
    void shouldKeepSnapshotAfterSubmitThreadClears()
            throws Exception {
        try (Pool pool = new Pool()) {
            ThreadLocalRequestCorrelationContext.set(CORRELATION_A);
            ThreadLocalRequestCorrelationContext.attachMdc(
                    CORRELATION_A
            );

            CountDownLatch done = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<RequestCorrelation> seen =
                    new AtomicReference<>();
            AtomicReference<String> seenMdc =
                    new AtomicReference<>();

            pool.worker().execute(() -> {
                awaitQuietly(release);

                seen.set(
                        ThreadLocalRequestCorrelationContext
                                .currentOrNull()
                );
                seenMdc.set(MDC.get(
                        ThreadLocalRequestCorrelationContext
                                .MDC_REQUEST_ID
                ));
                done.countDown();
            });

            // execute() 已在提交线程同步完成捕获；
            // 调用线程随即清理自身上下文。
            ThreadLocalRequestCorrelationContext.clear();
            ThreadLocalRequestCorrelationContext.clearMdc();

            release.countDown();

            assertTrue(
                    done.await(5, TimeUnit.SECONDS),
                    "worker task did not finish in time"
            );

            // worker 仍持有提交时的快照。
            assertEquals(CORRELATION_A, seen.get());
            assertEquals("req-a", seenMdc.get());
        }
    }

    @Test
    void shouldRestorePreexistingCorrelationAfterDecoratedTask() {
        ThreadLocalRequestCorrelationContext.clear();
        ThreadLocalRequestCorrelationContext.clearMdc();

        // 提交时快照 B。
        ThreadLocalRequestCorrelationContext.set(CORRELATION_B);

        Runnable decorated = new RequestCorrelationTaskDecorator()
                .decorate(() -> {
                    assertEquals(
                            CORRELATION_B,
                            ThreadLocalRequestCorrelationContext
                                    .currentOrNull()
                    );
                    assertEquals(
                            "req-b",
                            MDC.get(
                                    ThreadLocalRequestCorrelationContext
                                            .MDC_REQUEST_ID
                            )
                    );
                });

        // worker 线程“原来已有”上下文 A。
        ThreadLocalRequestCorrelationContext.set(CORRELATION_A);
        ThreadLocalRequestCorrelationContext.attachMdc(
                CORRELATION_A
        );

        decorated.run();

        // 任务结束后必须恢复原值 A。
        assertEquals(
                CORRELATION_A,
                ThreadLocalRequestCorrelationContext.currentOrNull()
        );
        assertEquals(
                "req-a",
                MDC.get(
                        ThreadLocalRequestCorrelationContext
                                .MDC_REQUEST_ID
                )
        );
        assertEquals(
                "trace-a",
                MDC.get(
                        ThreadLocalRequestCorrelationContext
                                .MDC_TRACE_ID
                )
        );

        ThreadLocalRequestCorrelationContext.clear();
        ThreadLocalRequestCorrelationContext.clearMdc();
    }

    @Test
    void shouldClearStaleWorkerStateWhenSubmitThreadHasNoCorrelation()
            throws Exception {
        // 提交线程无关联上下文：decorate 捕获的必须是 null 快照。
        ThreadLocalRequestCorrelationContext.clear();
        ThreadLocalRequestCorrelationContext.clearMdc();

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Observation> inside =
                new AtomicReference<>();
        AtomicReference<Observation> after =
                new AtomicReference<>();
        AtomicReference<Throwable> failure =
                new AtomicReference<>();

        Runnable decorated = new RequestCorrelationTaskDecorator()
                .decorate(() -> inside.set(snapshot()));

        Thread worker = new Thread(() -> {
            try {
                // 人工给 worker 放入上一个请求的旧关联与 MDC。
                ThreadLocalRequestCorrelationContext
                        .set(CORRELATION_B);
                ThreadLocalRequestCorrelationContext
                        .attachMdc(CORRELATION_B);

                decorated.run();

                after.set(snapshot());
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                ThreadLocalRequestCorrelationContext.clear();
                ThreadLocalRequestCorrelationContext.clearMdc();
                done.countDown();
            }
        });

        worker.start();

        assertTrue(
                done.await(5, TimeUnit.SECONDS),
                "worker task did not finish in time"
        );
        assertNull(failure.get());

        // runnable 内必须看不到 worker 残留的旧关联。
        Observation insideObservation = inside.get();

        assertNotNull(insideObservation);
        assertNull(insideObservation.correlation());
        assertNull(insideObservation.requestIdFromMdc());
        assertNull(insideObservation.traceIdFromMdc());

        // runnable 结束后，worker 原有 CORRELATION_B 与 MDC
        // 必须被正确恢复。
        Observation afterObservation = after.get();

        assertNotNull(afterObservation);
        assertEquals(
                CORRELATION_B,
                afterObservation.correlation()
        );
        assertEquals(
                "req-b",
                afterObservation.requestIdFromMdc()
        );
        assertEquals(
                "trace-b",
                afterObservation.traceIdFromMdc()
        );
    }

    private static Observation observe(
            ThreadPoolTaskExecutor worker
    ) throws InterruptedException {
        return observeWithThreadName(worker).observation();
    }

    private static ThreadObservation observeWithThreadName(
            ThreadPoolTaskExecutor worker
    ) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<RequestCorrelation> correlation =
                new AtomicReference<>();
        AtomicReference<String> requestIdFromMdc =
                new AtomicReference<>();
        AtomicReference<String> traceIdFromMdc =
                new AtomicReference<>();
        AtomicReference<String> threadName =
                new AtomicReference<>();

        worker.execute(() -> {
            threadName.set(
                    Thread.currentThread().getName()
            );
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

        return new ThreadObservation(
                threadName.get(),
                new Observation(
                        correlation.get(),
                        requestIdFromMdc.get(),
                        traceIdFromMdc.get()
                )
        );
    }

    private static Observation snapshot() {
        return new Observation(
                ThreadLocalRequestCorrelationContext
                        .currentOrNull(),
                MDC.get(
                        ThreadLocalRequestCorrelationContext
                                .MDC_REQUEST_ID
                ),
                MDC.get(
                        ThreadLocalRequestCorrelationContext
                                .MDC_TRACE_ID
                )
        );
    }

    private static void setAuthentication(String username) {
        SecurityContext context =
                SecurityContextHolder.createEmptyContext();

        context.setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        username,
                        "password",
                        List.of()
                )
        );

        SecurityContextHolder.setContext(context);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Pool implements AutoCloseable {

        private final ThreadPoolTaskExecutor worker;
        private final DelegatingSecurityContextAsyncTaskExecutor stream;

        Pool() {
            worker = new ThreadPoolTaskExecutor();

            worker.setCorePoolSize(1);
            worker.setMaxPoolSize(1);
            worker.setQueueCapacity(10);
            worker.setThreadNamePrefix(
                    "correlation-decorator-test-"
            );
            worker.setTaskDecorator(
                    new RequestCorrelationTaskDecorator()
            );
            worker.afterPropertiesSet();

            stream =
                    new DelegatingSecurityContextAsyncTaskExecutor(
                            worker
                    );
        }

        ThreadPoolTaskExecutor worker() {
            return worker;
        }

        DelegatingSecurityContextAsyncTaskExecutor stream() {
            return stream;
        }

        @Override
        public void close() {
            worker.shutdown();
        }
    }

    private record Observation(
            RequestCorrelation correlation,
            String requestIdFromMdc,
            String traceIdFromMdc
    ) {
    }

    private record ThreadObservation(
            String threadName,
            Observation observation
    ) {
    }
}
