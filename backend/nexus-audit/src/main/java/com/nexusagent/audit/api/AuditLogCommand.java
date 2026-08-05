package com.nexusagent.audit.api;

import java.util.Objects;

public record AuditLogCommand (
        long tenantId,
        AuditActorType actorType,
        Long actorId,
        String action,
        String resourceType,
        Long resourceId,
        Long toolExecutionId,
        AuditResult result,
        String requestId,
        String traceId,
        String ipAddress,
        Object beforeData,
        Object afterData,
        String errorCode,
        String errorMessage
){

    public AuditLogCommand{
        if(tenantId <= 0){
            throw new IllegalArgumentException ("tenantId must be positive");
        }

        Objects.requireNonNull(actorType,"actorType must not be null");
        Objects.requireNonNull(result,"result must not be null");

        if(actorType != AuditActorType.SYSTEM && actorId == null){
            throw new IllegalArgumentException (
                    "actorId is required for USER AND AGENT actors"
            );
        }

        if(action == null || action.isBlank()){
            throw new IllegalArgumentException ("action must not be blank");
        }

        if(resourceType == null || resourceType.isBlank()){
            throw new IllegalArgumentException ("resourceType must not be blank");
        }
    }
}
