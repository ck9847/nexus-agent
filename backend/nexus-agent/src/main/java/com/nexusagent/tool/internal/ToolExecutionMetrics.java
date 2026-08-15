package com.nexusagent.tool.internal;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * create_ticket 工具执行的生命周期指标。
 *
 * <p>单一计数器 {@value #METRIC_NAME}，标签：
 * <ul>
 *     <li>{@code tool}：固定 {@code create_ticket}；</li>
 *     <li>{@code replayed}：{@code true|false}；</li>
 *     <li>{@code outcome}：REGISTERED / REPLAYED / SUCCEEDED /
 *         FAILED / CONFLICT / IN_PROGRESS。</li>
 * </ul>
 *
 * <p>幂等重放必须记 REPLAYED（replayed=true），绝不能被统计成
 * 第二次业务成功（REGISTERED / SUCCEEDED）。
 *
 * <p>关键不变量：所有 Micrometer RuntimeException 都在内部吞掉，
 * 指标异常绝不能改变业务结果——数据库已提交成功时，绝不因指标异常
 * 向调用方报告失败、把 execution 补偿为 FAILED 或破坏幂等重放语义。
 * 业务 Service 通过注入本 Bean 计数，不再直接调用
 * {@link MeterRegistry#counter(String, String...)}。
 */
@Component
public final class ToolExecutionMetrics {

    public static final String METRIC_NAME =
            "nexus.tool.execution";

    public static final String TAG_TOOL = "tool";
    public static final String TAG_REPLAYED = "replayed";
    public static final String TAG_OUTCOME = "outcome";

    public static final String TOOL_CREATE_TICKET =
            "create_ticket";

    public static final String OUTCOME_REGISTERED =
            "REGISTERED";
    public static final String OUTCOME_REPLAYED = "REPLAYED";
    public static final String OUTCOME_SUCCEEDED =
            "SUCCEEDED";
    public static final String OUTCOME_FAILED = "FAILED";
    public static final String OUTCOME_CONFLICT = "CONFLICT";
    public static final String OUTCOME_IN_PROGRESS =
            "IN_PROGRESS";

    private final MeterRegistry registry;

    public ToolExecutionMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(
                registry,
                "registry must not be null"
        );
    }

    public void count(String outcome, boolean replayed) {
        try {
            registry.counter(
                    METRIC_NAME,
                    TAG_TOOL, TOOL_CREATE_TICKET,
                    TAG_REPLAYED, Boolean.toString(replayed),
                    TAG_OUTCOME, outcome
            ).increment();
        } catch (RuntimeException ignored) {
            // observability must never change business outcome
        }
    }
}
