package com.nexusagent.audit.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.audit.internal.persistence.AuditLogMapper;
import com.nexusagent.audit.internal.persistence.AuditLogRow;
import com.nexusagent.common.id.IdGenerator;
import com.nexusagent.common.observability.RequestCorrelation;
import com.nexusagent.common.observability.RequestCorrelationProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyBatisAuditLogWriterTest {

    private static final RequestCorrelation CORRELATION =
            new RequestCorrelation(
                    "ctx-req-1",
                    "ctx-trace-1",
                    "10.0.0.9"
            );

    private final AuditLogMapper mapper = mock(AuditLogMapper.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RequestCorrelationProvider correlationProvider =
            mock(RequestCorrelationProvider.class);

    private MyBatisAuditLogWriter writer;

    @BeforeEach
    void setUp() {
        writer = new MyBatisAuditLogWriter(
                mapper,
                idGenerator,
                objectMapper,
                correlationProvider
        );
    }

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
    void shouldFillAllCorrelationFieldsFromContext() {
        when(idGenerator.nextId()).thenReturn(101L);
        when(mapper.insert(any())).thenReturn(1);

        when(correlationProvider.currentCorrelation())
                .thenReturn(Optional.of(CORRELATION));

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

        writer.write(command);

        ArgumentCaptor<AuditLogRow> captor =
                ArgumentCaptor.forClass(AuditLogRow.class);

        verify(mapper).insert(captor.capture());

        AuditLogRow row = captor.getValue();

        assertEquals(
                "ctx-req-1",
                row.getRequestId()
        );
        assertEquals(
                "ctx-trace-1",
                row.getTraceId()
        );
        assertEquals(
                "10.0.0.9",
                row.getIpAddress()
        );
    }

    @Test
    void shouldPreferExplicitFieldsOverContext() {
        when(idGenerator.nextId()).thenReturn(102L);
        when(mapper.insert(any())).thenReturn(1);

        when(correlationProvider.currentCorrelation())
                .thenReturn(Optional.of(CORRELATION));

        AuditLogCommand command = new AuditLogCommand(
                10L,
                AuditActorType.SYSTEM,
                null,
                "TENANT_CREATED",
                "TENANT",
                20L,
                null,
                AuditResult.SUCCESS,
                "explicit-req",
                "explicit-trace",
                "192.168.1.1",
                null,
                null,
                null,
                null
        );

        writer.write(command);

        ArgumentCaptor<AuditLogRow> captor =
                ArgumentCaptor.forClass(AuditLogRow.class);

        verify(mapper).insert(captor.capture());

        AuditLogRow row = captor.getValue();

        assertEquals(
                "explicit-req",
                row.getRequestId()
        );
        assertEquals(
                "explicit-trace",
                row.getTraceId()
        );
        assertEquals(
                "192.168.1.1",
                row.getIpAddress()
        );
    }

    @Test
    void shouldAllowBackgroundWriteWithoutContext() {
        when(idGenerator.nextId()).thenReturn(103L);
        when(mapper.insert(any())).thenReturn(1);

        // Optional.empty() 表示正常无 HTTP 上下文，允许写入 null。
        when(correlationProvider.currentCorrelation())
                .thenReturn(Optional.empty());

        AuditLogCommand command = new AuditLogCommand(
                10L,
                AuditActorType.SYSTEM,
                null,
                "BACKGROUND_JOB",
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

        writer.write(command);

        ArgumentCaptor<AuditLogRow> captor =
                ArgumentCaptor.forClass(AuditLogRow.class);

        verify(mapper).insert(captor.capture());

        AuditLogRow row = captor.getValue();

        assertNull(row.getRequestId());
        assertNull(row.getTraceId());
        assertNull(row.getIpAddress());
    }

    @Test
    void shouldPropagateProviderFailureInsteadOfTreatingItAsNoContext() {
        when(idGenerator.nextId()).thenReturn(105L);

        // Provider 实现自身的真实故障：绝不能像旧实现那样
        // 捕获 IllegalStateException 并误判为"没有请求上下文"。
        IllegalStateException providerFailure =
                new IllegalStateException("provider boom");

        when(correlationProvider.currentCorrelation())
                .thenThrow(providerFailure);

        AuditLogCommand command = new AuditLogCommand(
                10L,
                AuditActorType.SYSTEM,
                null,
                "BACKGROUND_JOB",
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

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> writer.write(command)
        );

        assertSame(providerFailure, thrown);

        verify(mapper, never()).insert(any());
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

    @Test
    void shouldKeepCorrelationOutOfPayloadJson() {
        when(idGenerator.nextId()).thenReturn(104L);
        when(mapper.insert(any())).thenReturn(1);

        when(correlationProvider.currentCorrelation())
                .thenReturn(Optional.of(CORRELATION));

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
                Map.of("beforeStatus", "DRAFT"),
                Map.of("status", "ACTIVE"),
                null,
                null
        );

        writer.write(command);

        ArgumentCaptor<AuditLogRow> captor =
                ArgumentCaptor.forClass(AuditLogRow.class);

        verify(mapper).insert(captor.capture());

        AuditLogRow row = captor.getValue();

        assertEquals(
                "ctx-req-1",
                row.getRequestId()
        );
        assertEquals(
                "ctx-trace-1",
                row.getTraceId()
        );

        String beforeJson = row.getBeforeJson();
        String afterJson = row.getAfterJson();

        assertEquals(
                "{\"beforeStatus\":\"DRAFT\"}",
                beforeJson
        );
        assertEquals(
                "{\"status\":\"ACTIVE\"}",
                afterJson
        );

        // requestId/traceId 不得进入 beforeJson/afterJson。
        for (String payload : new String[]{beforeJson, afterJson}) {
            assertEquals(-1, payload.indexOf("ctx-req-1"));
            assertEquals(-1, payload.indexOf("ctx-trace-1"));
            assertEquals(-1, payload.indexOf("requestId"));
            assertEquals(-1, payload.indexOf("traceId"));
        }
    }
}
