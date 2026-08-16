package com.nexusagent.conversation.internal;

import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.model.api.ChatModelErrorCategory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 会话 turn 与模型调用的安全指标边界。
 *
 * <p>关键不变量：本类内部的每一次 Micrometer 调用都被
 * {@code catch (RuntimeException)} 保护。指标异常绝不能：
 * <ul>
 *     <li>把一次已经成功的 turn 变成失败；</li>
 *     <li>覆盖正在传播的模型异常（例如 finally 中的 stop 失败）；</li>
 *     <li>触发 failService 或任何业务补偿路径。</li>
 * </ul>
 * 业务 Service 不再直接调用
 * {@link MeterRegistry#timer(String, String...)} 或
 * {@link MeterRegistry#counter(String, String...)}。
 *
 * <p>标签必须保持低基数：模型调用指标只允许 {@code provider} /
 * {@code outcome} / {@code error_category}。禁止加入 modelName、
 * tenantId、conversationId、userId、异常消息或 provider 原始错误正文。
 */
@Component
public final class ConversationTurnMetrics {

    public static final String TURN_DURATION_METRIC =
            "nexus.conversation.turn";

    public static final String MODEL_CALL_METRIC =
            "nexus.model.call";

    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_PROVIDER = "provider";
    public static final String TAG_ERROR_CATEGORY =
            "error_category";

    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_FAILURE = "failure";

    public static final String ERROR_CATEGORY_NONE = "NONE";
    public static final String ERROR_CATEGORY_RATE_LIMIT =
            "RATE_LIMIT";
    public static final String ERROR_CATEGORY_AUTHENTICATION =
            "AUTHENTICATION";
    public static final String ERROR_CATEGORY_TIMEOUT =
            "TIMEOUT";
    public static final String ERROR_CATEGORY_PROVIDER_ERROR =
            "PROVIDER_ERROR";
    public static final String ERROR_CATEGORY_MALFORMED_RESPONSE =
            "MALFORMED_RESPONSE";
    public static final String ERROR_CATEGORY_STREAM_INTERRUPTED =
            "STREAM_INTERRUPTED";

    private final MeterRegistry registry;

    public ConversationTurnMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(
                registry,
                "registry must not be null"
        );

        preRegisterModelCallSeries();
    }

    /**
     * 为固定 provider/outcome/error_category 组合建立零基线。
     *
     * <p>若失败序列只在首次错误时才出现，Prometheus 的
     * {@code increase(...[window])} 缺少错误前的 0 样本，可能漏掉
     * 第一项认证失败。这里的组合全部来自有限枚举，基数有严格上界。
     */
    private void preRegisterModelCallSeries() {
        for (AgentModelProvider provider
                : AgentModelProvider.values()) {
            registerModelTimer(
                    provider.name(),
                    OUTCOME_SUCCESS,
                    ERROR_CATEGORY_NONE
            );

            for (ChatModelErrorCategory category
                    : ChatModelErrorCategory.values()) {
                registerModelTimer(
                        provider.name(),
                        OUTCOME_FAILURE,
                        errorCategoryTag(category)
                );
            }
        }
    }

    private void registerModelTimer(
            String provider,
            String outcome,
            String errorCategory
    ) {
        try {
            registry.timer(
                    MODEL_CALL_METRIC,
                    TAG_PROVIDER, provider,
                    TAG_OUTCOME, outcome,
                    TAG_ERROR_CATEGORY, errorCategory
            );
        } catch (RuntimeException ignored) {
            // 指标预注册失败同样不得阻止应用启动或业务调用。
        }
    }

    /**
     * 安全启动一个 Timer 观察。
     *
     * <p>启动失败（例如注册表时钟读取异常）时返回 no-op 观察：
     * 其 {@link Sample#stop(String, String...)} 不做任何事、
     * 绝不抛错。
     */
    public Sample startTimer() {
        Timer.Sample sample;

        try {
            sample = Timer.start(registry);
        } catch (RuntimeException ignored) {
            // 指标启动失败：退化为 no-op 观察。
            return new Sample(null);
        }

        return new Sample(sample);
    }

    /**
     * 安全递增计数器：任何 Micrometer RuntimeException 都被吞掉。
     */
    public void incrementCounter(String name, String... tags) {
        try {
            registry.counter(name, tags).increment();
        } catch (RuntimeException ignored) {
            // 指标异常绝不能影响调用方业务。
        }
    }

    /**
     * 把内部错误分类折叠成低基数 {@code error_category} 标签。
     *
     * <p>折叠规则：
     * <ul>
     *     <li>CONNECTION、PROVIDER_UNAVAILABLE、CONTENT_FILTERED
     *         -> PROVIDER_ERROR（provider 侧失败/拒绝）；</li>
     *     <li>INVALID_REQUEST -> MALFORMED_RESPONSE
     *         （请求/响应协议错误折叠到同一桶）；</li>
     *     <li>其余分类一一对应。</li>
     * </ul>
     */
    public static String errorCategoryTag(
            ChatModelErrorCategory category
    ) {
        Objects.requireNonNull(
                category,
                "category must not be null"
        );

        return switch (category) {
            case AUTHENTICATION ->
                    ERROR_CATEGORY_AUTHENTICATION;
            case RATE_LIMIT ->
                    ERROR_CATEGORY_RATE_LIMIT;
            case TIMEOUT ->
                    ERROR_CATEGORY_TIMEOUT;
            case STREAM_INTERRUPTED ->
                    ERROR_CATEGORY_STREAM_INTERRUPTED;
            case CONNECTION,
                    PROVIDER_UNAVAILABLE,
                    CONTENT_FILTERED ->
                    ERROR_CATEGORY_PROVIDER_ERROR;
            case MALFORMED_RESPONSE,
                    INVALID_REQUEST ->
                    ERROR_CATEGORY_MALFORMED_RESPONSE;
        };
    }

    /**
     * 一个安全的 Timer 观察：{@code stop} 绝不抛异常。
     */
    public final class Sample {

        private final Timer.Sample sample;

        private Sample(Timer.Sample sample) {
            this.sample = sample;
        }

        /**
         * 安全结束观察：timer 创建或记录失败全部吞掉；
         * no-op 观察直接返回。
         */
        public void stop(String metricName, String... tags) {
            if (sample == null) {
                return;
            }

            try {
                sample.stop(registry.timer(metricName, tags));
            } catch (RuntimeException ignored) {
                // 指标异常绝不能覆盖业务结果或业务异常。
            }
        }
    }
}
