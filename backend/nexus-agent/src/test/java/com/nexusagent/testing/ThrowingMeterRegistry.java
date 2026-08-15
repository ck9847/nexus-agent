package com.nexusagent.testing;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 测试用 MeterRegistry：可按需在 Timer 启动、timer 创建或
 * counter 创建三个故障点抛出 {@link IllegalStateException}，
 * 用于验证"指标异常绝不能改变业务结果"的故障注入测试。
 *
 * <p>默认行为与 {@link SimpleMeterRegistry} 完全一致，所有故障
 * 开关在构造后由测试显式打开。
 */
public class ThrowingMeterRegistry extends SimpleMeterRegistry {

    private boolean throwOnConfig;
    private boolean throwOnTimer;
    private boolean throwOnCounter;

    public ThrowingMeterRegistry() {
        super(SimpleConfig.DEFAULT, new io.micrometer
                .core.instrument.MockClock());
    }

    public ThrowingMeterRegistry(Clock clock) {
        super(SimpleConfig.DEFAULT, clock);
    }

    /**
     * 让 {@link Timer#start(MeterRegistry)} 抛错
     * （注册表 {@code config().clock()} 读取失败）。
     */
    public void throwOnTimerStart() {
        throwOnConfig = true;
    }

    /**
     * 让 {@code registry.timer(...)} 抛错。
     */
    public void throwOnTimerCreation() {
        throwOnTimer = true;
    }

    /**
     * 让 {@code registry.counter(...)} 抛错。
     */
    public void throwOnCounterCreation() {
        throwOnCounter = true;
    }

    @Override
    public Config config() {
        if (throwOnConfig) {
            throw new IllegalStateException(
                    "metrics timer start boom"
            );
        }

        return super.config();
    }

    @Override
    public Timer timer(String name, Iterable<Tag> tags) {
        if (throwOnTimer) {
            throw new IllegalStateException(
                    "metrics timer creation boom"
            );
        }

        return super.timer(name, tags);
    }

    @Override
    public Counter counter(String name, Iterable<Tag> tags) {
        if (throwOnCounter) {
            throw new IllegalStateException(
                    "metrics counter creation boom"
            );
        }

        return super.counter(name, tags);
    }
}
