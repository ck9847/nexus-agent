package com.nexusagent.identity.internal.persistence;

import com.nexusagent.identity.domain.UserStatus;

public record UserRow(
        long id,
        long tenantId,
        String username,
        String email,
        String passwordHash,
        String displayName,
        UserStatus status,
        int version
) {
}
