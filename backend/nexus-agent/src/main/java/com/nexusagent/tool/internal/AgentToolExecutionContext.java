package com.nexusagent.tool.internal;

public record AgentToolExecutionContext(
        long tenantId,
        long requesterUserId,
        long conversationId,
        long agentId,
        long requestMessageId,
        long toolExecutionId,
        String toolCallId
) {

    public AgentToolExecutionContext {
        requirePositive(tenantId, "tenantId");
        requirePositive(
                requesterUserId,
                "requesterUserId"
        );
        requirePositive(
                conversationId,
                "conversationId"
        );
        requirePositive(agentId, "agentId");
        requirePositive(
                requestMessageId,
                "requestMessageId"
        );
        requirePositive(
                toolExecutionId,
                "toolExecutionId"
        );

        if (toolCallId == null) {
            throw new IllegalArgumentException(
                    "toolCallId must not be null"
            );
        }

        toolCallId = toolCallId.trim();

        if (toolCallId.isBlank()) {
            throw new IllegalArgumentException(
                    "toolCallId must not be blank"
            );
        }

        if (toolCallId.length() > 128) {
            throw new IllegalArgumentException(
                    "toolCallId must not exceed "
                            + "128 characters"
            );
        }
    }

    private static void requirePositive(
            long value,
            String field
    ) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    field + " must be positive"
            );
        }
    }
}
