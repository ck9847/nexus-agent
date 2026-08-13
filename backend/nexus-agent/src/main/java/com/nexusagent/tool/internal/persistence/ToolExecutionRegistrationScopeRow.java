package com.nexusagent.tool.internal.persistence;

public record ToolExecutionRegistrationScopeRow(
        long conversationId,
        long tenantId,
        long userId,
        long agentId,
        long requestMessageId
) {
}