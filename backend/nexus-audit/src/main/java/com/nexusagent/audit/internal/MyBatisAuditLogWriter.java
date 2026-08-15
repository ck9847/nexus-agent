package com.nexusagent.audit.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.internal.persistence.AuditLogMapper;
import com.nexusagent.audit.internal.persistence.AuditLogRow;
import com.nexusagent.common.id.IdGenerator;
import com.nexusagent.common.observability.RequestCorrelation;
import com.nexusagent.common.observability.RequestCorrelationProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class MyBatisAuditLogWriter implements AuditLogWriter {

    private final AuditLogMapper auditLogMapper;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final RequestCorrelationProvider correlationProvider;

    public MyBatisAuditLogWriter(
            AuditLogMapper auditLogMapper,
            IdGenerator idGenerator,
            ObjectMapper objectMapper,
            RequestCorrelationProvider correlationProvider
    ) {
        this.auditLogMapper = Objects.requireNonNull(
                auditLogMapper,
                "auditLogMapper must not be null"
        );
        this.idGenerator = Objects.requireNonNull(
                idGenerator,
                "idGenerator must not be null"
        );
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper must not be null"
        );
        this.correlationProvider = Objects.requireNonNull(
                correlationProvider,
                "correlationProvider must not be null"
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(AuditLogCommand command) {
        RequestCorrelation correlation =
                currentCorrelationOrNull();

        String requestId = firstNonNull(
                command.requestId(),
                correlation == null
                        ? null
                        : correlation.requestId()
        );
        String traceId = firstNonNull(
                command.traceId(),
                correlation == null
                        ? null
                        : correlation.traceId()
        );
        String ipAddress = firstNonNull(
                command.ipAddress(),
                correlation == null
                        ? null
                        : correlation.ipAddress()
        );

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
                requestId,
                traceId,
                ipAddress,
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

    /**
     * 显式命令值优先；命令未提供时回落到当前请求关联。
     *
     * <p>{@link Optional#empty()} 表示无 HTTP 上下文（后台任务），
     * 允许写入 null；Provider 实现自身的真实故障必须作为异常
     * 向外传播，绝不能误判为"没有请求上下文"。
     */
    private RequestCorrelation currentCorrelationOrNull() {
        return correlationProvider
                .currentCorrelation()
                .orElse(null);
    }

    private static String firstNonNull(
            String explicit,
            String fallback
    ) {
        return explicit != null ? explicit : fallback;
    }

    private String serialize(Object value) {
        if (value == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize audit data.",
                    exception
            );
        }
    }
}
