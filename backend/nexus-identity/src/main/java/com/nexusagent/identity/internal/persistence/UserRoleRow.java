package com.nexusagent.identity.internal.persistence;

public record UserRoleRow(
        long tenantId,
        long userId,
        long roleId,
        Long assignedBy
) {
}
