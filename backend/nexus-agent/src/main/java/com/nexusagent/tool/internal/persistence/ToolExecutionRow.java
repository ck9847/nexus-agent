package com.nexusagent.tool.internal.persistence;

import com.nexusagent.tool.domain.ToolExecutionStatus;

import java.time.Instant;

public record ToolExecutionRow(
        long id,
        long tenantId,
        long conversationId,
        long agentId,
        Long requestMessageId,
        Long resultMessageId,
        String toolCallId,
        String toolName,
        String idempotencyKey,
        String inputJson,
        String outputJson,
        ToolExecutionStatus status,
        boolean approvalRequired,
        String resultEntityType,
        Long resultEntityId,
        String errorCode,
        String errorMessage,
        String traceId,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        Instant createdAt,
        Instant updatedAt
) {
}