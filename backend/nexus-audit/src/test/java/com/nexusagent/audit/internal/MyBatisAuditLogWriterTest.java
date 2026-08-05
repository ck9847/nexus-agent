package com.nexusagent.audit.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.audit.internal.persistence.AuditLogMapper;
import com.nexusagent.audit.internal.persistence.AuditLogRow;
import com.nexusagent.common.id.IdGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyBatisAuditLogWriterTest {

    private final AuditLogMapper mapper = mock(AuditLogMapper.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final MyBatisAuditLogWriter writer =
            new MyBatisAuditLogWriter(mapper, idGenerator, objectMapper);

    @Test
    void shouldInsertSerializedAuditLog() {
        when(idGenerator.nextId()).thenReturn(100L);
        when(mapper.insert(any())).thenReturn(1);

        AuditLogCommand command = new AuditLogCommand(
                10L,
                AuditActorType.SYSTEM,
                null,
                "TENANT_CREATED",
                "TENANT",
                20L,
                null,
                AuditResult.SUCCESS,
                "request-1",
                "trace-1",
                "127.0.0.1",
                null,
                Map.of("status", "ACTIVE"),
                null,
                null
        );

        writer.write(command);

        ArgumentCaptor<AuditLogRow> captor =
                ArgumentCaptor.forClass(AuditLogRow.class);

        verify(mapper).insert(captor.capture());

        AuditLogRow row = captor.getValue();

        assertEquals(100L, row.getId());
        assertEquals(10L, row.getTenantId());
        assertEquals("SYSTEM", row.getActorType());
        assertEquals("TENANT_CREATED", row.getAction());
        assertEquals("{\"status\":\"ACTIVE\"}", row.getAfterJson());
    }

    @Test
    void shouldFailWhenMapperDoesNotInsertExactlyOneRow() {
        when(idGenerator.nextId()).thenReturn(100L);
        when(mapper.insert(any())).thenReturn(0);

        AuditLogCommand command = new AuditLogCommand(
                10L,
                AuditActorType.SYSTEM,
                null,
                "TENANT_CREATED",
                "TENANT",
                20L,
                null,
                AuditResult.SUCCESS,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThrows(
                IllegalStateException.class,
                () -> writer.write(command)
        );
    }
}