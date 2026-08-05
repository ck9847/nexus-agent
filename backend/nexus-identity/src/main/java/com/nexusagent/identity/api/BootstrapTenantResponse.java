package com.nexusagent.identity.api;

public record BootstrapTenantResponse(
        String tenantId,
        String adminUserId,
        String adminRoleId
) {
}