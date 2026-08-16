package com.nexusagent.model.api;

import java.util.Objects;

/**
 * 模型流消费侧（下游 handler/SSE 客户端）失败的标记异常。
 *
 * <p>当 {@link ChatModelStreamHandler} 的下游（例如 SSE 事件写入器）
 * 在流式消费过程中抛错时，accumulator 以本异常向外传播。它表示
 * <b>消费方</b>已经不可用——典型场景是客户端断开连接——而不是
 * 供应商故障。
 *
 * <p>弹性组件依赖这一类型做精确判定：
 * <ul>
 *     <li>供应商熔断器<b>不</b>把它记为供应商失败（客户端断开
 *         不应触发熔断）；</li>
 *     <li>模型调用安全重试<b>不</b>重试它（客户端已经离开，
 *         重试只是浪费一次供应商调用）。</li>
 * </ul>
 */
public class ChatModelStreamConsumerException
        extends RuntimeException {

    public ChatModelStreamConsumerException(
            String safeMessage,
            Throwable cause
    ) {
        super(
                requireSafeMessage(safeMessage),
                Objects.requireNonNull(
                        cause,
                        "cause must not be null"
                )
        );
    }

    private static String requireSafeMessage(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "safeMessage must not be blank"
            );
        }

        return value;
    }
}
