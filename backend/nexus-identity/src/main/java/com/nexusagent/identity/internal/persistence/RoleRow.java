package com.nexusagent.identity.internal.persistence;

public record RoleRow(
        long id,
        long tenantId,
        String code,
        String name,
        String description
) {
}
