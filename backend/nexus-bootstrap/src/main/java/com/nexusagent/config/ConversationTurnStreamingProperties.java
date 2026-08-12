package com.nexusagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 会话轮次流式的执行器与超时配置。
 *
 * <p>默认值通过 {@link DefaultValue} 声明，绑定失败会在
 * 应用启动阶段尽早暴露配置错误。
 */
@ConfigurationProperties(
        prefix = "nexus.conversation.streaming"
)
public record ConversationTurnStreamingProperties(
        @DefaultValue("2m")
        Duration timeout,

        @DefaultValue("4")
        int corePoolSize,

        @DefaultValue("16")
        int maxPoolSize,

        @DefaultValue("100")
        int queueCapacity
) {

    public ConversationTurnStreamingProperties {
        if (timeout == null
                || timeout.isNegative()
                || timeout.isZero()) {
            throw new IllegalArgumentException(
                    "timeout must be positive"
            );
        }

        if (corePoolSize < 1) {
            throw new IllegalArgumentException(
                    "corePoolSize must be at least 1"
            );
        }

        if (maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException(
                    "maxPoolSize must be at least corePoolSize"
            );
        }

        if (queueCapacity < 0) {
            throw new IllegalArgumentException(
                    "queueCapacity must not be negative"
            );
        }
    }
}
