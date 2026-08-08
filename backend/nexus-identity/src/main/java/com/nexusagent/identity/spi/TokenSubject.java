package com.nexusagent.identity.spi;

import java.util.List;
import java.util.Objects;

public record TokenSubject(
        long userId,
        long tenantId,
        String username,
        List<String> roles
) {

    public TokenSubject{
        if (userId <= 0 || tenantId <= 0){
            throw new IllegalArgumentException(
                    "userId and tenantId must be positive"
            );
        }

        Objects.requireNonNull(username, "username must not be null");
        roles = List.copyOf(roles);
    }
}
