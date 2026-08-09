package com.nexusagent.agent.internal;

import com.nexusagent.agent.api.AgentAdministrationForbiddenException;
import com.nexusagent.agent.api.AgentDetailResponse;
import com.nexusagent.agent.api.AgentNotFoundException;
import com.nexusagent.agent.domain.AgentModelConfig;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.agent.domain.AgentStatus;
import com.nexusagent.agent.internal.persistence.AgentDetailRow;
import com.nexusagent.agent.internal.persistence.AgentMapper;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAgentQueryServiceTest {

    private static final CurrentActor ADMIN =
            new CurrentActor(
                    101L,
                    202L,
                    "admin",
                    Set.of("ADMIN")
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-09T01:00:00Z"
            );

    private static final Instant UPDATED_AT =
            Instant.parse(
                    "2026-08-09T02:00:00Z"
            );

    private static final String MODEL_CONFIG_JSON =
            """
            {
              "temperature": 0.2,
              "topP": 0.9,
              "maxOutputTokens": 2048
            }
            """;

    private static final AgentModelConfig MODEL_CONFIG =
            new AgentModelConfig(
                    new BigDecimal("0.2"),
                    new BigDecimal("0.9"),
                    2_048
            );

    @Mock
    private CurrentActorProvider currentActorProvider;

    @Mock
    private AgentMapper agentMapper;

    @Mock
    private AgentModelConfigJsonCodec modelConfigJsonCodec;

    private DefaultAgentQueryService service;

    @BeforeEach
    void setUp() {
        service = new DefaultAgentQueryService(
                currentActorProvider,
                agentMapper,
                modelConfigJsonCodec
        );
    }

    @Test
    void shouldReturnAgentDetailsWithinCurrentTenant() {
        AgentDetailRow row = detailRow(
                202L,
                "support-agent",
                MODEL_CONFIG_JSON
        );

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ADMIN);

        when(agentMapper.findDetailByTenantIdAndCode(
                202L,
                "support-agent"
        )).thenReturn(Optional.of(row));

        when(modelConfigJsonCodec.decode(
                MODEL_CONFIG_JSON
        )).thenReturn(MODEL_CONFIG);

        AgentDetailResponse response =
                service.getByCode(
                        " SUPPORT-AGENT "
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
                        "Support Agent",
                        response.name()
                ),
                () -> assertEquals(
                        "Handles enterprise support requests.",
                        response.description()
                ),
                () -> assertEquals(
                        "You are an enterprise support agent.",
                        response.systemPrompt()
                ),
                () -> assertEquals(
                        AgentModelProvider.OPENAI,
                        response.modelProvider()
                ),
                () -> assertEquals(
                        "gpt-5-mini",
                        response.modelName()
                ),
                () -> assertEquals(
                        MODEL_CONFIG,
                        response.modelConfig()
                ),
                () -> assertEquals(
                        AgentStatus.ACTIVE,
                        response.status()
                ),
                () -> assertEquals(
                        "101",
                        response.createdByUserId()
                ),
                () -> assertEquals(
                        3,
                        response.version()
                ),
                () -> assertEquals(
                        CREATED_AT,
                        response.createdAt()
                ),
                () -> assertEquals(
                        UPDATED_AT,
                        response.updatedAt()
                )
        );

        verify(agentMapper)
                .findDetailByTenantIdAndCode(
                        202L,
                        "support-agent"
                );

        verify(modelConfigJsonCodec)
                .decode(MODEL_CONFIG_JSON);
    }

    @Test
    void shouldReturnNullModelConfigWhenDatabaseValueIsNull() {
        AgentDetailRow row = detailRow(
                202L,
                "support-agent",
                null
        );

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ADMIN);

        when(agentMapper.findDetailByTenantIdAndCode(
                202L,
                "support-agent"
        )).thenReturn(Optional.of(row));

        AgentDetailResponse response =
                service.getByCode(
                        "support-agent"
                );

        assertNull(response.modelConfig());

        verify(modelConfigJsonCodec)
                .decode(null);
    }

    @Test
    void shouldThrowNotFoundWhenAgentIsAbsent() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ADMIN);

        when(agentMapper.findDetailByTenantIdAndCode(
                202L,
                "missing-agent"
        )).thenReturn(Optional.empty());

        assertThrows(
                AgentNotFoundException.class,
                () -> service.getByCode(
                        "missing-agent"
                )
        );

        verifyNoInteractions(
                modelConfigJsonCodec
        );
    }

    @Test
    void shouldRejectNonAdministratorBeforeCodeValidation() {
        CurrentActor member = new CurrentActor(
                102L,
                202L,
                "member",
                Set.of("MEMBER")
        );

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(member);

        assertThrows(
                AgentAdministrationForbiddenException.class,
                () -> service.getByCode(null)
        );

        verifyNoInteractions(
                agentMapper,
                modelConfigJsonCodec
        );
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "   ",
            "1invalid"
    })
    void shouldRejectInvalidAgentCodes(
            String invalidCode
    ) {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ADMIN);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.getByCode(
                        invalidCode
                )
        );

        verifyNoInteractions(
                agentMapper,
                modelConfigJsonCodec
        );
    }

    @Test
    void shouldRejectInconsistentDetailRow() {
        AgentDetailRow wrongTenantRow =
                detailRow(
                        999L,
                        "support-agent",
                        MODEL_CONFIG_JSON
                );

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ADMIN);

        when(agentMapper.findDetailByTenantIdAndCode(
                202L,
                "support-agent"
        )).thenReturn(
                Optional.of(wrongTenantRow)
        );

        assertThrows(
                IllegalStateException.class,
                () -> service.getByCode(
                        "support-agent"
                )
        );

        verifyNoInteractions(
                modelConfigJsonCodec
        );
    }

    private static AgentDetailRow detailRow(
            long tenantId,
            String code,
            String modelConfigJson
    ) {
        return new AgentDetailRow(
                901L,
                tenantId,
                code,
                "Support Agent",
                "Handles enterprise support requests.",
                "You are an enterprise support agent.",
                AgentModelProvider.OPENAI,
                "gpt-5-mini",
                modelConfigJson,
                AgentStatus.ACTIVE,
                101L,
                3,
                CREATED_AT,
                UPDATED_AT
        );
    }
}