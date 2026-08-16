package com.nexusagent.config;

import com.nexusagent.conversation.internal.ConversationTurnMetrics;
import com.nexusagent.observability.RequestCorrelationTaskDecorator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 有界、安全的会话轮次流式执行器装配。
 *
 * <p>{@code conversationTurnStreamExecutor} 必须使用无显式
 * {@code SecurityContext} 参数的
 * {@link DelegatingSecurityContextAsyncTaskExecutor} 构造器：
 * 每次 {@code submit} 时从当前请求线程捕获 JWT {@code SecurityContext}
 * 并设置给 worker，任务结束后由
 * {@code DelegatingSecurityContextRunnable} 清理线程上下文，
 * 避免上下文在池线程之间泄漏。
 *
 * <p>worker 线程池挂载 {@link RequestCorrelationTaskDecorator}，
 * 提交时同步捕获请求关联（requestId/traceId/ipAddress + MDC），
 * worker 执行前恢复、执行后还原原值；外层
 * {@link DelegatingSecurityContextAsyncTaskExecutor} 继续负责
 * JWT {@code SecurityContext} 的传播，两者互不干扰。
 *
 * <p>worker 线程池采用有界队列 + AbortPolicy，容量满时
 * {@code submit} 抛 {@code TaskRejectedException}，
 * 由 {@code ConversationTurnStreamController} 映射为 503。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
        ConversationTurnStreamingProperties.class
)
public class ConversationTurnAsyncConfiguration {

    @Bean(name = "conversationTurnWorkerExecutor")
    ThreadPoolTaskExecutor conversationTurnWorkerExecutor(
            ConversationTurnStreamingProperties properties,
            TaskDecorator requestCorrelationTaskDecorator,
            ConversationTurnMetrics turnMetrics
    ) {
        ThreadPoolTaskExecutor executor =
                new ThreadPoolTaskExecutor();

        // 请求关联传播之外叠加队列等待计时：
        // decorate 发生在提交线程（入队前），
        // run 发生在 worker 线程（出队后），两者之差即排队耗时。
        executor.setTaskDecorator(
                new CompositeTaskDecorator(
                        new QueueWaitTimingTaskDecorator(
                                turnMetrics
                        ),
                        requestCorrelationTaskDecorator
                )
        );
        executor.setCorePoolSize(
                properties.corePoolSize()
        );
        executor.setMaxPoolSize(
                properties.maxPoolSize()
        );
        executor.setQueueCapacity(
                properties.queueCapacity()
        );
        executor.setThreadNamePrefix(
                "conversation-turn-"
        );
        executor.setRejectedExecutionHandler(
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.setWaitForTasksToCompleteOnShutdown(
                false
        );
        executor.setAwaitTerminationSeconds(10);

        // 不手动 initialize()：生命周期交给 Spring。
        return executor;
    }

    @Bean(name = "conversationTurnStreamExecutor")
    AsyncTaskExecutor conversationTurnStreamExecutor(
            @Qualifier("conversationTurnWorkerExecutor")
            ThreadPoolTaskExecutor delegate
    ) {
        return new DelegatingSecurityContextAsyncTaskExecutor(
                delegate
        );
    }

    /**
     * 为 worker 线程池绑定 executor 指标
     * （队列深度、活跃线程、池利用率、已完成任务数）。
     *
     * <p>绑定失败绝不阻止启动：执行器本身可用性优先于其观测。
     */
    @Bean
    Object conversationTurnExecutorMetrics(
            MeterRegistry meterRegistry,
            @Qualifier("conversationTurnWorkerExecutor")
            ThreadPoolTaskExecutor executor
    ) {
        try {
            ExecutorServiceMetrics.monitor(
                    meterRegistry,
                    executor.getThreadPoolExecutor(),
                    "conversationTurnWorker",
                    "nexus.conversation"
            );
        } catch (RuntimeException ignored) {
            // 指标绑定失败不影响执行器。
        }

        return new Object();
    }

    /**
     * 顺序组合两个 TaskDecorator：先排队计时、再请求关联。
     */
    record CompositeTaskDecorator(
            TaskDecorator outer,
            TaskDecorator inner
    ) implements TaskDecorator {

        @Override
        public Runnable decorate(Runnable runnable) {
            return outer.decorate(inner.decorate(runnable));
        }
    }

    /**
     * 记录任务从提交到开始执行的排队等待时间
     * （{@code nexus.conversation.turn.queue.wait}）。
     */
    private static final class
    QueueWaitTimingTaskDecorator implements TaskDecorator {

        private final ConversationTurnMetrics metrics;

        private QueueWaitTimingTaskDecorator(
                ConversationTurnMetrics metrics
        ) {
            this.metrics = metrics;
        }

        @Override
        public Runnable decorate(Runnable runnable) {
            ConversationTurnMetrics.Sample sample =
                    metrics.startTimer();

            return () -> {
                sample.stop(
                        ConversationTurnMetrics
                                .QUEUE_WAIT_METRIC
                );

                runnable.run();
            };
        }
    }
}
