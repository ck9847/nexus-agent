package com.nexusagent.resilience;

import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelStreamConsumerException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged
        .TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged
        .TaggedRateLimiterMetrics;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Resilience4j 程序化装配。
 *
 * <p>不使用 {@code resilience4j-spring-boot3} starter：熔断需要
 * 按 {@link ChatModelErrorCategory} 自定义“哪些异常算供应商失败”
 * 的记录/忽略谓词，yaml 无法表达；程序化注册表让语义集中在
 * 一处并可直接单测。
 *
 * <p>注册表的 Micrometer 指标在这里统一绑定
 * （{@code resilience4j_circuitbreaker_*}、
 * {@code resilience4j_ratelimiter_*}），惰性创建的 per-tenant
 * 限流器实例同样会被注册表事件监听捕获。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ResilienceProperties.class)
public class ResilienceConfiguration {

    @Bean
    public RateLimiterRegistry rateLimiterRegistry(
            ResilienceProperties properties
    ) {
        // 注册表默认配置按 user 级（更严格）初始化；
        // tenant 级实例在 ConversationTurnRateLimiter 中
        // 以显式配置创建。
        RateLimiterConfig defaultConfig =
                RateLimiterConfig.custom()
                        .limitForPeriod(
                                properties.rateLimit()
                                        .userLimitForPeriod()
                        )
                        .limitRefreshPeriod(
                                properties.rateLimit()
                                        .userRefreshPeriod()
                        )
                        // 非阻塞：无可用许可立即拒绝，
                        // 由调用方映射为 429。
                        .timeoutDuration(Duration.ZERO)
                        .build();

        return RateLimiterRegistry.of(defaultConfig);
    }

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(
            ResilienceProperties properties
    ) {
        CircuitBreakerConfig config =
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(
                                properties.circuitBreaker()
                                        .slidingWindowSize()
                        )
                        .minimumNumberOfCalls(
                                properties.circuitBreaker()
                                        .minimumNumberOfCalls()
                        )
                        .failureRateThreshold(
                                properties.circuitBreaker()
                                        .failureRateThreshold()
                        )
                        .waitDurationInOpenState(
                                properties.circuitBreaker()
                                        .waitDurationInOpenState()
                        )
                        .permittedNumberOfCallsInHalfOpenState(
                                properties.circuitBreaker()
                                        .permittedNumberOfCallsInHalfOpenState()
                        )
                        // 客户端断开（消费侧失败）与供应商流外中断
                        // 不是供应商健康度信号，熔断完全忽略：
                        // 既不计失败，也不污染成功样本。
                        .ignoreException(
                                throwable ->
                                        throwable
                                                instanceof
                                                ChatModelStreamConsumerException
                                                || (throwable
                                                instanceof
                                                ChatModelException failure
                                                && failure.category()
                                                == ChatModelErrorCategory
                                                .STREAM_INTERRUPTED)
                        )
                        // 网关已把传输层/供应商协议异常翻译为
                        // ChatModelException；只有它计入熔断统计。
                        // 逃逸出的其他 RuntimeException 属于代码缺陷，
                        // 交由常规错误处理而不是熔断。
                        .recordException(
                                throwable ->
                                        throwable
                                                instanceof
                                                ChatModelException
                        )
                        .build();

        return CircuitBreakerRegistry.of(config);
    }

    /**
     * 模型安全重试的退避间隔函数：initialBackoff 起步，
     * 每次重试乘以 backoffMultiplier。
     */
    @Bean
    public IntervalFunction modelRetryBackoff(
            ResilienceProperties properties
    ) {
        return IntervalFunction.ofExponentialBackoff(
                properties.modelRetry().initialBackoff(),
                properties.modelRetry().backoffMultiplier()
        );
    }

    @Bean
    public ResilienceMetricsBinder resilienceMetricsBinder(
            MeterRegistry meterRegistry,
            RateLimiterRegistry rateLimiterRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry
    ) {
        return new ResilienceMetricsBinder(
                meterRegistry,
                rateLimiterRegistry,
                circuitBreakerRegistry
        );
    }

    /**
     * 绑定两个注册表的指标。绑定失败（例如受限的测试
     * registry）绝不能阻止应用启动：弹性本身优先于其观测。
     */
    static final class ResilienceMetricsBinder {

        private ResilienceMetricsBinder(
                MeterRegistry meterRegistry,
                RateLimiterRegistry rateLimiterRegistry,
                CircuitBreakerRegistry circuitBreakerRegistry
        ) {
            try {
                TaggedRateLimiterMetrics
                        .ofRateLimiterRegistry(
                                rateLimiterRegistry
                        )
                        .bindTo(meterRegistry);
                TaggedCircuitBreakerMetrics
                        .ofCircuitBreakerRegistry(
                                circuitBreakerRegistry
                        )
                        .bindTo(meterRegistry);
            } catch (RuntimeException ignored) {
                // 指标绑定失败不影响弹性语义。
            }
        }
    }
}
