package com.nexusagent.audit.internal.persistence;

public final class AuditLogRow {

    private final long id;
    private final long tenantId;
    private final String actorType;
    private final Long actorId;
    private final String action;
    private final String resourceType;
    private final Long resourceId;
    private final Long toolExecutionId;
    private final String result;
    private final String requestId;
    private final String traceId;
    private final String ipAddress;
    private final String beforeJson;
    private final String afterJson;
    private final String errorCode;
    private final String errorMessage;

    public AuditLogRow(
            long id,
            long tenantId,
            String actorType,
            Long actorId,
            String action,
            String resourceType,
            Long resourceId,
            Long toolExecutionId,
            String result,
            String requestId,
            String traceId,
            String ipAddress,
            String beforeJson,
            String afterJson,
            String errorCode,
            String errorMessage
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.actorType = actorType;
        this.actorId = actorId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.toolExecutionId = toolExecutionId;
        this.result = result;
        this.requestId = requestId;
        this.traceId = traceId;
        this.ipAddress = ipAddress;
        this.beforeJson = beforeJson;
        this.afterJson = afterJson;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public long getId() {
        return id;
    }

    public long getTenantId() {
        return tenantId;
    }

    public String getActorType() {
        return actorType;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public Long getToolExecutionId() {
        return toolExecutionId;
    }

    public String getResult() {
        return result;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getBeforeJson() {
        return beforeJson;
    }

    public String getAfterJson() {
        return afterJson;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}