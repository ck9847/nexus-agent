package com.nexusagent.conversation.internal;

import com.nexusagent.agent.api.ActiveAgentRuntime;
import com.nexusagent.model.api.ChatModelRequest;

import java.time.Instant;
import java.util.Objects;

public record PreparedConversationTurn(
        long tenantId,
        long userId,
        long conversationId,
        ActiveAgentRuntime agent,
        long userMessageId,
        long userSequenceNo,
        long assistantMessageId,
        long assistantSequenceNo,
        int conversationVersion,
        Instant preparedAt,
        ChatModelRequest modelRequest
) {

    public PreparedConversationTurn {
        if (tenantId <= 0
                || userId <= 0
                || conversationId <= 0
                || userMessageId <= 0
                || assistantMessageId <= 0) {
            throw new IllegalArgumentException(
                    "Prepared turn IDs must be positive"
            );
        }

        if (userMessageId == assistantMessageId) {
            throw new IllegalArgumentException(
                    "Turn message IDs must be distinct"
            );
        }

        if (userSequenceNo <= 0
                || assistantSequenceNo
                != userSequenceNo + 1) {
            throw new IllegalArgumentException(
                    "Turn message sequences must be consecutive"
            );
        }

        if (conversationVersion < 1) {
            throw new IllegalArgumentException(
                    "conversationVersion must be positive"
            );
        }

        Objects.requireNonNull(agent, "agent must not be null");
        Objects.requireNonNull(
                preparedAt,
                "preparedAt must not be null"
        );
        Objects.requireNonNull(
                modelRequest,
                "modelRequest must not be null"
        );

        if (agent.tenantId() != tenantId) {
            throw new IllegalArgumentException(
                    "Agent tenant must match turn tenant"
            );
        }
    }
}