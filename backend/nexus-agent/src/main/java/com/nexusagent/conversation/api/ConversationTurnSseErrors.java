package com.nexusagent.conversation.api;

import com.nexusagent.agent.api.AgentNotFoundException;
import com.nexusagent.model.api.ChatModelException;

import java.util.Objects;

/**
 * 将流式会话轮次失败映射为对客户端安全的 SSE 错误。
 *
 * <p>{@link Throwable#getMessage()} 永远不会直接发送给客户端：
 * 每种失败都映射到固定的错误码与消息，只有
 * {@link ChatModelException} 会携带其安全分类
 * （{@code CHAT_MODEL_<CATEGORY>}）与重试标记。
 */
final class ConversationTurnSseErrors {

    private static final String CHAT_MODEL_TURN_FAILED =
            "Chat model turn failed";

    private ConversationTurnSseErrors() {
    }

    static ConversationTurnSseError from(Throwable failure) {
        Objects.requireNonNull(
                failure,
                "failure must not be null"
        );

        if (failure instanceof ConversationNotFoundException) {
            return fixed(
                    "CONVERSATION_NOT_FOUND",
                    "Conversation not found",
                    false
            );
        }

        if (failure instanceof ConversationNotActiveException) {
            return fixed(
                    "CONVERSATION_NOT_ACTIVE",
                    "Conversation is not active",
                    false
            );
        }

        if (failure instanceof ConversationTurnInProgressException) {
            return fixed(
                    "CONVERSATION_TURN_IN_PROGRESS",
                    "A conversation turn is already in progress",
                    true
            );
        }

        if (failure instanceof AgentNotFoundException) {
            return fixed(
                    "ACTIVE_AGENT_NOT_FOUND",
                    "Active Agent not found",
                    false
            );
        }

        if (failure instanceof ChatModelException chatModelFailure) {
            return new ConversationTurnSseError(
                    "CHAT_MODEL_"
                            + chatModelFailure.category().name(),
                    CHAT_MODEL_TURN_FAILED,
                    chatModelFailure.retryable()
            );
        }

        if (failure instanceof IllegalArgumentException) {
            return fixed(
                    "INVALID_ARGUMENT",
                    "Invalid conversation turn request",
                    false
            );
        }

        return fixed(
                "INTERNAL_ERROR",
                "Conversation turn failed",
                false
        );
    }

    private static ConversationTurnSseError fixed(
            String errorCode,
            String message,
            boolean retryable
    ) {
        return new ConversationTurnSseError(
                errorCode,
                message,
                retryable
        );
    }
}
