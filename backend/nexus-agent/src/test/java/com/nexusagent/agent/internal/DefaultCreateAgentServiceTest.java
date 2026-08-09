package com.nexusagent.agent.internal;

import com.nexusagent.agent.api.AgentAdministrationForbiddenException;
import com.nexusagent.agent.api.AgentCodeAlreadyExistsException;
import com.nexusagent.agent.api.CreateAgentRequest;
import com.nexusagent.agent.api.CreateAgentResponse;
import com.nexusagent.agent.domain.AgentModelConfig;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.agent.domain.AgentStatus;
import com.nexusagent.agent.internal.persistence.AgentMapper;
import com.nexusagent.agent.internal.persistence.AgentRow;
import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.common.id.IdGenerator;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultCreateAgentServiceTest {

    private static final CurrentActor ADMIN =
            new CurrentActor(
                    101L,
                    202L,
                    "admin",
                    Set.of("ADMIN")
            );

    private static final AgentModelConfig VALID_CONFIG =
            new AgentModelConfig(
                    new BigDecimal("0.2"),
                    new BigDecimal("0.9"),
                    2_048
            );

    private static final String MODEL_CONFIG_JSON =
            """
            {"temperature":0.2,"topP":0.9,\
            "maxOutputTokens":2048}
            """;

    @Mock
    private CurrentActorProvider currentActorProvider;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private AgentMapper agentMapper;

    @Mock
    private AgentModelConfigJsonCodec modelConfigJsonCodec;

    @Mock
    private AuditLogWriter auditLogWriter;

    private DefaultCreateAgentService service;

    @BeforeEach
    void setUp() {
        service = new DefaultCreateAgentService(
                currentActorProvider,
                idGenerator,
                agentMapper,
                modelConfigJsonCodec,
                auditLogWriter
        );
    }

    @Test
    void shouldCreateNormalizedDraftAgentAndAuditSafeMetadata() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ADMIN);

        when(agentMapper.existsByTenantIdAndCode(
                202L,
                "support-agent"
        )).thenReturn(false);

        when(modelConfigJsonCodec.encode(
                same(VALID_CONFIG)
        )).thenReturn(MODEL_CONFIG_JSON);

        when(idGenerator.nextId())
                .thenReturn(901L);

        when(agentMapper.insert(
                any(AgentRow.class)
        )).thenReturn(1);

        CreateAgentResponse response = service.create(
                new CreateAgentRequest(
                        " SUPPORT-AGENT ",
                        " Support Agent ",
                        "   ",
                        """
                         You are helpful.
                         Keep answers short.
                         """,
                        AgentModelProvider.OPENAI,
                        " gpt-5-mini ",
                        VALID_CONFIG
                )
        );

        ArgumentCaptor<AgentRow> rowCaptor =
                ArgumentCaptor.forClass(
                        AgentRow.class
                );

        ArgumentCaptor<AuditLogCommand> auditCaptor =
                ArgumentCaptor.forClass(
                        AuditLogCommand.class
                );

        verify(agentMapper).insert(
                rowCaptor.capture()
        );

        verify(auditLogWriter).write(
                auditCaptor.capture()
        );

        AgentRow row = rowCaptor.getValue();
        AuditLogCommand audit =
                auditCaptor.getValue();

        assertAll(
                () -> assertEquals(901L, row.id()),
                () -> assertEquals(202L, row.tenantId()),
                () -> assertEquals(
                        "support-agent",
                        row.code()
                ),
                () -> assertEquals(
                        "Support Agent",
                        row.name()
                ),
                () -> assertNull(row.description()),
                () -> assertEquals(
                        """
                        You are helpful.
                        Keep answers short.
                        """.trim(),
                        row.systemPrompt()
                ),
                () -> assertEquals(
                        AgentModelProvider.OPENAI,
                        row.modelProvider()
                ),
                () -> assertEquals(
                        "gpt-5-mini",
                        row.modelName()
                ),
                () -> assertEquals(
                        MODEL_CONFIG_JSON,
                        row.modelConfigJson()
                ),
                () -> assertEquals(
                        AgentStatus.DRAFT,
                        row.status()
                ),
                () -> assertEquals(
                        101L,
                        row.createdByUserId()
                ),
                () -> assertEquals(0, row.version())
        );

        assertAll(
                () -> assertEquals(
                        202L,
                        audit.tenantId()
                ),
                () -> assertEquals(
                        AuditActorType.USER,
                        audit.actorType()
                ),
                () -> assertEquals(
                        Long.valueOf(101L),
                        audit.actorId()
                ),
                () -> assertEquals(
                        "AGENT_CREATED",
                        audit.action()
                ),
                () -> assertEquals(
                        "AGENT",
                        audit.resourceType()
                ),
                () -> assertEquals(
                        Long.valueOf(901L),
                        audit.resourceId()
                ),
                () -> assertNull(
                        audit.toolExecutionId()
                ),
                () -> assertEquals(
                        AuditResult.SUCCESS,
                        audit.result()
                ),
                () -> assertNull(audit.beforeData()),
                () -> assertEquals(
                        Map.of(
                                "code",
                                "support-agent",
                                "name",
                                "Support Agent",
                                "modelProvider",
                                "OPENAI",
                                "modelName",
                                "gpt-5-mini",
                                "status",
                                "DRAFT",
                                "version",
                                0
                        ),
                        audit.afterData()
                )
        );

        assertAll(
                () -> assertEquals(
                        "901",
                        response.agentId()
                ),
                () -> assertEquals(
                        "support-agent",
                        response.code()
                ),
                () -> assertEquals(
                        AgentStatus.DRAFT,
                        response.status()
                ),
                () -> assertEquals(
                        0,
                        response.version()
                )
        );
    }

    @Test
    void shouldRejectNonAdministratorBeforeValidation() {
        CurrentActor member = new CurrentActor(
                102L,
                202L,
                "member",
                Set.of("MEMBER")
        );

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(member);

        CreateAgentRequest invalidRequest =
                new CreateAgentRequest(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );

        assertThrows(
                AgentAdministrationForbiddenException.class,
                () -> service.create(invalidRequest)
        );

        verifyNoInteractions(
                idGenerator,
                agentMapper,
                modelConfigJsonCodec,
                auditLogWriter
        );
    }

    @Test
    void shouldRejectExistingNormalizedCodeBeforeGeneratingId() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ADMIN);

        when(agentMapper.existsByTenantIdAndCode(
                202L,
                "support-agent"
        )).thenReturn(true);

        AgentCodeAlreadyExistsException exception =
                assertThrows(
                        AgentCodeAlreadyExistsException.class,
                        () -> service.create(validRequest(
                                VALID_CONFIG
                        ))
                );

        assertEquals(
                "support-agent",
                exception.getAgentCode()
        );

        verify(agentMapper, never())
                .insert(any(AgentRow.class));

        verifyNoInteractions(
                idGenerator,
                modelConfigJsonCodec,
                auditLogWriter
        );
    }

    @Test
    void shouldTranslateDuplicateKeyFromInsert() {
        DuplicateKeyException duplicate =
                new DuplicateKeyException(
                        "uk_agents_tenant_code"
                );

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ADMIN);

        when(agentMapper.existsByTenantIdAndCode(
                202L,
                "support-agent"
        )).thenReturn(false);

        when(modelConfigJsonCodec.encode(
                same(VALID_CONFIG)
        )).thenReturn(MODEL_CONFIG_JSON);

        when(idGenerator.nextId())
                .thenReturn(901L);

        when(agentMapper.insert(
                any(AgentRow.class)
        )).thenThrow(duplicate);

        AgentCodeAlreadyExistsException exception =
                assertThrows(
                        AgentCodeAlreadyExistsException.class,
                        () -> service.create(validRequest(
                                VALID_CONFIG
                        ))
                );

        assertAll(
                () -> assertEquals(
                        "support-agent",
                        exception.getAgentCode()
                ),
                () -> assertSame(
                        duplicate,
                        exception.getCause()
                )
        );

        verifyNoInteractions(auditLogWriter);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void shouldRejectUnexpectedInsertCount(
            int affectedRows
    ) {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ADMIN);

        when(agentMapper.existsByTenantIdAndCode(
                202L,
                "support-agent"
        )).thenReturn(false);

        when(modelConfigJsonCodec.encode(
                same(VALID_CONFIG)
        )).thenReturn(MODEL_CONFIG_JSON);

        when(idGenerator.nextId())
                .thenReturn(901L);

        when(agentMapper.insert(
                any(AgentRow.class)
        )).thenReturn(affectedRows);

        assertThrows(
                IllegalStateException.class,
                () -> service.create(validRequest(
                        VALID_CONFIG
                ))
        );

        verifyNoInteractions(auditLogWriter);
    }

    @ParameterizedTest
    @MethodSource("invalidModelConfigs")
    void shouldRejectInvalidModelConfigBeforePersistence(
            AgentModelConfig invalidConfig
    ) {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ADMIN);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.create(validRequest(
                        invalidConfig
                ))
        );

        verifyNoInteractions(
                idGenerator,
                agentMapper,
                modelConfigJsonCodec,
                auditLogWriter
        );
    }

    private static Stream<AgentModelConfig>
    invalidModelConfigs() {
        return Stream.of(
                new AgentModelConfig(
                        new BigDecimal("-0.1"),
                        null,
                        null
                ),
                new AgentModelConfig(
                        new BigDecimal("2.1"),
                        null,
                        null
                ),
                new AgentModelConfig(
                        null,
                        new BigDecimal("-0.1"),
                        null
                ),
                new AgentModelConfig(
                        null,
                        new BigDecimal("1.1"),
                        null
                ),
                new AgentModelConfig(
                        null,
                        null,
                        0
                ),
                new AgentModelConfig(
                        null,
                        null,
                        131_073
                )
        );
    }

    private static CreateAgentRequest validRequest(
            AgentModelConfig config
    ) {
        return new CreateAgentRequest(
                " SUPPORT-AGENT ",
                " Support Agent ",
                " Handles support requests. ",
                " You are an enterprise support agent. ",
                AgentModelProvider.OPENAI,
                " gpt-5-mini ",
                config
        );
    }
}