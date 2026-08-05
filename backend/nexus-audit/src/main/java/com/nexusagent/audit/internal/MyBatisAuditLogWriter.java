package com.nexusagent.audit.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.internal.persistence.AuditLogMapper;
import com.nexusagent.audit.internal.persistence.AuditLogRow;
import com.nexusagent.common.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MyBatisAuditLogWriter implements AuditLogWriter {

    private final AuditLogMapper auditLogMapper;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;

    public MyBatisAuditLogWriter(
            AuditLogMapper auditLogMapper,
            IdGenerator idGenerator,
            ObjectMapper objectMapper
    ){
        this.auditLogMapper = auditLogMapper;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(AuditLogCommand command) {
        AuditLogRow row = new AuditLogRow(
                idGenerator.nextId(),
                command.tenantId(),
                command.actorType().name(),
                command.actorId(),
                command.action(),
                command.resourceType(),
                command.resourceId(),
                command.toolExecutionId(),
                command.result().name(),
                command.requestId(),
                command.traceId(),
                command.ipAddress(),
                serialize(command.beforeData()),
                serialize(command.afterData()),
                command.errorCode(),
                command.errorMessage()
        );

        int affectedRows = auditLogMapper.insert(row);
        if (affectedRows != 1) {
            throw new IllegalStateException(
                    "Expected one audit log row to be inserted."
            );
        }
    }

    private String serialize(Object value) {
        if(value == null){
            return null;
        }

        try{
            return objectMapper.writeValueAsString(value);
        } catch(JsonProcessingException exception){
            throw new IllegalStateException(
                    "Failed to serialize audit data.",
                    exception
            );
        }
    }
}
