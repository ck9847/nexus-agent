package com.nexusagent.identity.internal.persistence;

import com.nexusagent.identity.domain.UserStatus;

public record LoginUserRow(
        long id,
        long tenantId,
        String username,
        String passwordHash,
        UserStatus status,
        String roleCodes
) {
}