package com.nexusagent.config;

import com.nexusagent.conversation.internal.ConversationTurnMetrics;
import com.nexusagent.observability.RequestCorrelationTaskDecorator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link ConversationTurnAsyncConfiguration} 装配的有界
 * worker 池与 {@link DelegatingSecurityContextAsyncTaskExecutor}
 * 包装：
 *
 * <ul>
 *   <li>core/max/queue 从 {@link ConversationTurnStreamingProperties}
 *       读取，拒绝策略为 AbortPolicy；</li>
 *   <li>单参数构造器每次 {@code submit} 从提交线程捕获
 *       {@code SecurityContext}，worker 能看到；</li>
 *   <li>任务结束后由 {@code DelegatingSecurityContextRunnable}
 *       清空线程上下文，因此同一池线程连续执行两个不同租户
 *       上下文不会串，无认证上下文的任务也不会继承上一次任务的
 *       认证。</li>
 * </ul>
 *
 * <p>纯单元测试：直接调用配置类的 bean 工厂方法，不加载
 * Spring 上下文，也不会连接任何真实模型。
 */
class ConversationTurnAsyncConfigurationTest {

    private final ConversationTurnAsyncConfiguration configuration =
            new ConversationTurnAsyncConfiguration();

    private static ConversationTurnMetrics turnMetrics() {
        return new ConversationTurnMetrics(
                new SimpleMeterRegistry()
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldConfigureBoundedWorkerPool() {
        ThreadPoolTaskExecutor executor =
                configuration.conversationTurnWorkerExecutor(
                        properties(4, 16, 100),
                        new RequestCorrelationTaskDecorator(),
                        turnMetrics()
                );

        assertEquals(4, executor.getCorePoolSize());
        assertEquals(16, executor.getMaxPoolSize());
        assertEquals(100, executor.getQueueCapacity());
        assertTrue(
                executor.getThreadNamePrefix()
                        .startsWith("conversation-turn-")
        );

        // getThreadPoolExecutor() 要求池已初始化，
        // 初始化后从中读取实际装配的拒绝策略。
        executor.afterPropertiesSet();

        try {
            assertInstanceOf(
                    ThreadPoolExecutor.AbortPolicy.class,
                    executor.getThreadPoolExecutor()
                            .getRejectedExecutionHandler()
            );
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void shouldAttachRequestCorrelationDecoratorToWorkerPool()
            throws Exception {
        ThreadPoolTaskExecutor executor =
                configuration.conversationTurnWorkerExecutor(
                        properties(4, 16, 100),
                        new RequestCorrelationTaskDecorator(),
                        turnMetrics()
                );

        try {
            // 装饰器是 CompositeTaskDecorator：外层排队计时、
            // 内层请求关联传播。
            Object decorator = readTaskDecorator(executor);

            assertInstanceOf(
                    ConversationTurnAsyncConfiguration
                            .CompositeTaskDecorator.class,
                    decorator
            );

            Object inner = compositeInner(decorator);

            assertInstanceOf(
                    RequestCorrelationTaskDecorator.class,
                    inner
            );
        } finally {
            executor.shutdown();
        }
    }

    private static Object compositeInner(Object composite)
            throws Exception {
        java.lang.reflect.Field field =
                composite.getClass()
                        .getDeclaredField("inner");

        field.setAccessible(true);

        return field.get(composite);
    }

    @Test
    void shouldWrapWorkerExecutorWithSecurityContextAwareDecorator() {
        try (WorkerPool pool = boundedWorker(1, 1, 0)) {
            assertInstanceOf(
                    DelegatingSecurityContextAsyncTaskExecutor.class,
                    pool.stream()
            );
        }
    }

    @Test
    void shouldPropagateSecurityContextFromSubmittingThread()
            throws Exception {
        try (WorkerPool pool = boundedWorker(1, 1, 5)) {
            setAuthentication("tenant-a-user");

            Authentication seen =
                    runTaskCapture(pool.stream());

            assertNotNull(seen);
            assertEquals("tenant-a-user", seen.getName());
        }
    }

    @Test
    void shouldNotLeakContextAcrossTenantsOnSamePoolThread()
            throws Exception {
        try (WorkerPool pool = boundedWorker(1, 1, 5)) {
            AtomicReference<String> firstThread =
                    new AtomicReference<>();
            AtomicReference<String> secondThread =
                    new AtomicReference<>();

            setAuthentication("tenant-a-user");
            Authentication first =
                    runTaskCapture(
                            pool.stream(),
                            firstThread
                    );

            assertEquals(
                    "tenant-a-user",
                    first.getName()
            );

            setAuthentication("tenant-b-user");
            Authentication second =
                    runTaskCapture(
                            pool.stream(),
                            secondThread
                    );

            assertEquals(
                    "tenant-b-user",
                    second.getName()
            );

            // core=1 保证两个任务由同一个池线程顺序执行；
            // 各自只能看到自己提交时的认证，且线程名一致。
            assertEquals(
                    firstThread.get(),
                    secondThread.get()
            );
        }
    }

    @Test
    void shouldNotInheritAuthenticationForUnauthenticatedTask()
            throws Exception {
        try (WorkerPool pool = boundedWorker(1, 1, 5)) {
            setAuthentication("tenant-a-user");
            runTaskCapture(pool.stream());

            SecurityContextHolder.clearContext();

            Authentication seen =
                    runTaskCapture(pool.stream());

            assertNull(seen);
        }
    }

    @Test
    void shouldRejectTaskWhenPoolAndQueueAreFull()
            throws Exception {
        try (WorkerPool pool = boundedWorker(1, 1, 1)) {
            CountDownLatch firstStarted =
                    new CountDownLatch(1);
            CountDownLatch release =
                    new CountDownLatch(1);

            pool.stream().execute(() -> {
                firstStarted.countDown();
                awaitQuietly(release);
            });

            assertTrue(
                    firstStarted.await(
                            5,
                            TimeUnit.SECONDS
                    )
            );

            // 第 2 个进入队列（queueCapacity=1）。
            pool.stream().execute(() -> {
            });

            // 第 3 个：池满 + 队列满 → AbortPolicy → TaskRejectedException。
            assertThrows(
                    TaskRejectedException.class,
                    () -> pool.stream().execute(() -> {
                    })
            );

            release.countDown();
        }
    }

    private WorkerPool boundedWorker(
            int core,
            int max,
            int queue
    ) {
        return new WorkerPool(core, max, queue);
    }

    private static ConversationTurnStreamingProperties properties(
            int core,
            int max,
            int queue
    ) {
        return new ConversationTurnStreamingProperties(
                Duration.ofMinutes(2),
                core,
                max,
                queue
        );
    }

    private static void setAuthentication(
            String username
    ) {
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

    /**
     * {@code ThreadPoolTaskExecutor} 只有私有 {@code taskDecorator}
     * 字段、没有公开 getter，只能通过反射读取。
     */
    private static Object readTaskDecorator(
            ThreadPoolTaskExecutor executor
    ) throws Exception {
        Class<?> type = executor.getClass();

        while (type != null) {
            try {
                java.lang.reflect.Field field =
                        type.getDeclaredField("taskDecorator");

                field.setAccessible(true);

                return field.get(executor);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }

        throw new AssertionError(
                "taskDecorator field not found "
                        + "on executor hierarchy"
        );
    }

    private static Authentication runTaskCapture(
            AsyncTaskExecutor stream
    ) throws InterruptedException {
        return runTaskCapture(stream, new AtomicReference<>());
    }

    /**
     * 提交一个任务，捕获它在池线程中看到的认证与线程名。
     */
    private static Authentication runTaskCapture(
            AsyncTaskExecutor stream,
            AtomicReference<String> threadName
    ) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Authentication> seen =
                new AtomicReference<>();

        stream.execute(() -> {
            threadName.set(
                    Thread.currentThread().getName()
            );
            seen.set(
                    SecurityContextHolder
                            .getContext()
                            .getAuthentication()
            );
            done.countDown();
        });

        assertTrue(
                done.await(5, TimeUnit.SECONDS),
                "worker task did not complete in time"
        );

        return seen.get();
    }

    private static void awaitQuietly(
            CountDownLatch latch
    ) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class WorkerPool
            implements AutoCloseable {

        private final ThreadPoolTaskExecutor worker;
        private final AsyncTaskExecutor stream;

        WorkerPool(int core, int max, int queue) {
            ConversationTurnAsyncConfiguration configuration =
                    new ConversationTurnAsyncConfiguration();

            this.worker =
                    configuration.conversationTurnWorkerExecutor(
                            properties(core, max, queue),
                            new RequestCorrelationTaskDecorator(),
                            turnMetrics()
                    );
            this.worker.afterPropertiesSet();

            this.stream =
                    configuration.conversationTurnStreamExecutor(
                            worker
                    );
        }

        AsyncTaskExecutor stream() {
            return stream;
        }

        @Override
        public void close() {
            worker.shutdown();
        }
    }
}
