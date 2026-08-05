package com.nexusagent.audit.internal.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper {

    @Insert("""
            INSERT INTO audit_logs
            (
                id,
                tenant_id,
                actor_type,
                actor_id,
                action,
                resource_type,
                resource_id,
                tool_execution_id,
                result,
                request_id,
                trace_id,
                ip_address,
                before_json,
                after_json,
                error_code,
                error_message
            )
            VALUES
            (
                #{id},
                #{tenantId},
                #{actorType},
                #{actorId},
                #{action},
                #{resourceType},
                #{resourceId},
                #{toolExecutionId},
                #{result},
                #{requestId},
                #{traceId},
                #{ipAddress},
                #{beforeJson},
                #{afterJson},
                #{errorCode},
                #{errorMessage}
            )
            """)
    int insert(AuditLogRow row);
}