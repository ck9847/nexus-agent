package com.nexusagent.identity.internal.persistence;

import com.nexusagent.identity.domain.TenantStatus;

public record TenantRow(
        long id,
        String code,
        String name,
        TenantStatus status,
        int version
) {
}
