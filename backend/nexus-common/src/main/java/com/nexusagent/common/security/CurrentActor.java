package com.nexusagent.common.security;

import java.util.Objects;
import java.util.Set;

public record CurrentActor(
        long userId,
        long tenantId,
        String username,
        Set<String> roles
) {

    public CurrentActor {
        if (userId <= 0 || tenantId <= 0) {
            throw new IllegalArgumentException(
                    "userId and tenantId must be positive"
            );
        }

        Objects.requireNonNull(
                username,
                "username must not be null"
        );
        Objects.requireNonNull(
                roles,
                "roles must not be null"
        );

        username = username.trim();

        if (username.isBlank()) {
            throw new IllegalArgumentException(
                    "username must not be blank"
            );
        }

        roles = Set.copyOf(roles);

        if (roles.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException(
                    "roles must not contain blank values"
            );
        }
    }

    public boolean hasRole(String role) {
        return role != null && roles.contains(role);
    }
}