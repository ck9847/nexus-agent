package com.nexusagent.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
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
            ConversationTurnStreamingProperties properties
    ) {
        ThreadPoolTaskExecutor executor =
                new ThreadPoolTaskExecutor();

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
}
