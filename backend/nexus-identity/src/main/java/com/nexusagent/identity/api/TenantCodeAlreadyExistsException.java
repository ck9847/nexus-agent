package com.nexusagent.identity.api;

public final class TenantCodeAlreadyExistsException extends RuntimeException {

    private final String tenantCode;

    public TenantCodeAlreadyExistsException(String tenantCode) {
        super("Tenant code already exists: " + tenantCode);
        this.tenantCode = tenantCode;
    }

    public String getTenantCode() {
        return tenantCode;
    }
}
