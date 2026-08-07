package com.nexusagent.identity.api;

import java.util.List;

public record LoginResponse(
        String tokenType,
        String accessToken,
        long expiresInSeconds,
        String userId,
        String tenantId,
        List<String> roles
) {
}