package com.nexusagent.agent.internal;

import com.nexusagent.agent.api.ActiveAgentRuntime;
import com.nexusagent.agent.api.AgentNotFoundException;
import com.nexusagent.agent.domain.AgentModelConfig;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.agent.domain.AgentStatus;
import com.nexusagent.agent.internal.persistence.ActiveAgentRuntimeRow;
import com.nexusagent.agent.internal.persistence.AgentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAgentRuntimeLookupTest {

    private static final long TENANT_ID = 202L;
    private static final long AGENT_ID = 901L;
    private static final String CODE = "support-agent";
    private static final String SYSTEM_PROMPT =
            "You are a support agent.";
    private static final String MODEL_NAME = "gpt-5";
    private static final String MODEL_CONFIG_JSON =
            "{\"temperature\":0.7}";

    @Mock
    private AgentMapper agentMapper;

    @Mock
    private AgentModelConfigJsonCodec modelConfigCodec;

    private DefaultAgentRuntimeLookup lookup;

    @BeforeEach
    void setUp() {
        lookup = new DefaultAgentRuntimeLookup(
                agentMapper,
                modelConfigCodec
        );
    }

    @Test
    void shouldReturnFullConfigForActiveAgent() {
        when(agentMapper.findActiveRuntimeByTenantIdAndId(
                TENANT_ID,
                AGENT_ID
        )).thenReturn(Optional.of(activeRow()));

        AgentModelConfig config = new AgentModelConfig(
                new BigDecimal("0.7"),
                null,
                null
        );
        when(modelConfigCodec.decode(MODEL_CONFIG_JSON))
                .thenReturn(config);

        ActiveAgentRuntime result = lookup.requireActiveAgent(
                TENANT_ID,
                AGENT_ID
        );

        assertAll(
                () -> assertEquals(
                        AGENT_ID,
                        result.agentId()
                ),
                () -> assertEquals(
                        TENANT_ID,
                        result.tenantId()
                ),
                () -> assertEquals(CODE, result.code()),
                () -> assertEquals(
                        SYSTEM_PROMPT,
                        result.systemPrompt()
                ),
                () -> assertEquals(
                        AgentModelProvider.OPENAI,
                        result.modelProvider()
                ),
                () -> assertEquals(
                        MODEL_NAME,
                        result.modelName()
                ),
                () -> assertSame(
                        config,
                        result.modelConfig()
                )
        );
    }

    @Test
    void shouldReturnNullConfigWhenModelConfigJsonIsNull() {
        when(agentMapper.findActiveRuntimeByTenantIdAndId(
                TENANT_ID,
                AGENT_ID
        )).thenReturn(Optional.of(activeRowWithJson(null)));

        when(modelConfigCodec.decode(null))
                .thenReturn(null);

        ActiveAgentRuntime result = lookup.requireActiveAgent(
                TENANT_ID,
                AGENT_ID
        );

        assertNull(result.modelConfig());
    }

    @Test
    void shouldPassExactTenantIdAndAgentIdToMapper() {
        when(agentMapper.findActiveRuntimeByTenantIdAndId(
                TENANT_ID,
                AGENT_ID
        )).thenReturn(Optional.of(activeRow()));

        lookup.requireActiveAgent(TENANT_ID, AGENT_ID);

        verify(agentMapper)
                .findActiveRuntimeByTenantIdAndId(
                        TENANT_ID,
                        AGENT_ID
                );
    }

    @Test
    void shouldThrowNotFoundWhenAgentDoesNotExist() {
        when(agentMapper.findActiveRuntimeByTenantIdAndId(
                TENANT_ID,
                AGENT_ID
        )).thenReturn(Optional.empty());

        AgentNotFoundException exception = assertThrows(
                AgentNotFoundException.class,
                () -> lookup.requireActiveAgent(
                        TENANT_ID,
                        AGENT_ID
                )
        );

        assertEquals(
                "Agent not found",
                exception.getMessage()
        );
    }

    @ParameterizedTest
    @EnumSource(
            value = AgentStatus.class,
            names = {"DRAFT", "DISABLED"}
    )
    void shouldHideNonActiveAgentAsNotFound(AgentStatus status) {
        // mapper SQL 过滤 status = 'ACTIVE'，非 ACTIVE agent 表现为查不到
        when(agentMapper.findActiveRuntimeByTenantIdAndId(
                TENANT_ID,
                AGENT_ID
        )).thenReturn(Optional.empty());

        assertThrows(
                AgentNotFoundException.class,
                () -> lookup.requireActiveAgent(
                        TENANT_ID,
                        AGENT_ID
                )
        );
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void shouldRejectNonPositiveTenantIdWithoutTouchingMapper(
            long tenantId
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> lookup.requireActiveAgent(
                        tenantId,
                        AGENT_ID
                )
        );

        verifyNoInteractions(agentMapper);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void shouldRejectNonPositiveAgentIdWithoutTouchingMapper(
            long agentId
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> lookup.requireActiveAgent(
                        TENANT_ID,
                        agentId
                )
        );

        verifyNoInteractions(agentMapper);
    }

    @Test
    void shouldRejectNullOptionalFromMapper() {
        when(agentMapper.findActiveRuntimeByTenantIdAndId(
                TENANT_ID,
                AGENT_ID
        )).thenReturn(null);

        assertThrows(
                NullPointerException.class,
                () -> lookup.requireActiveAgent(
                        TENANT_ID,
                        AGENT_ID
                )
        );
    }

    @Test
    void shouldRejectRowWithMismatchedTenant() {
        when(agentMapper.findActiveRuntimeByTenantIdAndId(
                TENANT_ID,
                AGENT_ID
        )).thenReturn(Optional.of(row(
                999L,
                AGENT_ID,
                AgentStatus.ACTIVE
        )));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> lookup.requireActiveAgent(
                        TENANT_ID,
                        AGENT_ID
                )
        );

        assertEquals(
                "Agent mapper returned a row "
                        + "outside the requested scope",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectRowWithMismatchedId() {
        when(agentMapper.findActiveRuntimeByTenantIdAndId(
                TENANT_ID,
                AGENT_ID
        )).thenReturn(Optional.of(row(
                TENANT_ID,
                888L,
                AgentStatus.ACTIVE
        )));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> lookup.requireActiveAgent(
                        TENANT_ID,
                        AGENT_ID
                )
        );

        assertEquals(
                "Agent mapper returned a row "
                        + "outside the requested scope",
                exception.getMessage()
        );
    }

    @ParameterizedTest
    @EnumSource(
            value = AgentStatus.class,
            names = {"DRAFT", "DISABLED"}
    )
    void shouldRejectRowWithNonActiveStatus(
            AgentStatus status
    ) {
        when(agentMapper.findActiveRuntimeByTenantIdAndId(
                TENANT_ID,
                AGENT_ID
        )).thenReturn(Optional.of(row(
                TENANT_ID,
                AGENT_ID,
                status
        )));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> lookup.requireActiveAgent(
                        TENANT_ID,
                        AGENT_ID
                )
        );

        assertEquals(
                "Agent mapper returned "
                        + "an invalid runtime row",
                exception.getMessage()
        );
    }

    @ParameterizedTest
    @MethodSource("invalidRuntimeRows")
    void shouldRejectInvalidRuntimeRow(
            ActiveAgentRuntimeRow row
    ) {
        when(agentMapper.findActiveRuntimeByTenantIdAndId(
                TENANT_ID,
                AGENT_ID
        )).thenReturn(Optional.of(row));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> lookup.requireActiveAgent(
                        TENANT_ID,
                        AGENT_ID
                )
        );

        assertEquals(
                "Agent mapper returned "
                        + "an invalid runtime row",
                exception.getMessage()
        );

        // validateRow 在 decode 之前拒绝，codec 不应被触碰
        verifyNoInteractions(modelConfigCodec);
    }

    @Test
    void shouldPropagateCodecException() {
        when(agentMapper.findActiveRuntimeByTenantIdAndId(
                TENANT_ID,
                AGENT_ID
        )).thenReturn(Optional.of(activeRow()));

        IllegalStateException cause =
                new IllegalStateException(
                        "malformed config"
                );
        when(modelConfigCodec.decode(MODEL_CONFIG_JSON))
                .thenThrow(cause);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> lookup.requireActiveAgent(
                        TENANT_ID,
                        AGENT_ID
                )
        );

        assertSame(cause, thrown);
    }

    private static Stream<ActiveAgentRuntimeRow>
    invalidRuntimeRows() {
        return Stream.of(
                rowWith(
                        null,
                        SYSTEM_PROMPT,
                        MODEL_NAME
                ),
                rowWith(
                        AgentModelProvider.OPENAI,
                        null,
                        MODEL_NAME
                ),
                rowWith(
                        AgentModelProvider.OPENAI,
                        "   ",
                        MODEL_NAME
                ),
                rowWith(
                        AgentModelProvider.OPENAI,
                        SYSTEM_PROMPT,
                        null
                ),
                rowWith(
                        AgentModelProvider.OPENAI,
                        SYSTEM_PROMPT,
                        "   "
                ),
                rowWithCode(null)
        );
    }

    private static ActiveAgentRuntimeRow row(
            long tenantId,
            long id,
            AgentStatus status
    ) {
        return new ActiveAgentRuntimeRow(
                id,
                tenantId,
                CODE,
                SYSTEM_PROMPT,
                AgentModelProvider.OPENAI,
                MODEL_NAME,
                MODEL_CONFIG_JSON,
                status
        );
    }

    private static ActiveAgentRuntimeRow activeRow() {
        return activeRowWithJson(MODEL_CONFIG_JSON);
    }

    private static ActiveAgentRuntimeRow activeRowWithJson(
            String modelConfigJson
    ) {
        return new ActiveAgentRuntimeRow(
                AGENT_ID,
                TENANT_ID,
                CODE,
                SYSTEM_PROMPT,
                AgentModelProvider.OPENAI,
                MODEL_NAME,
                modelConfigJson,
                AgentStatus.ACTIVE
        );
    }

    private static ActiveAgentRuntimeRow rowWith(
            AgentModelProvider modelProvider,
            String systemPrompt,
            String modelName
    ) {
        return new ActiveAgentRuntimeRow(
                AGENT_ID,
                TENANT_ID,
                CODE,
                systemPrompt,
                modelProvider,
                modelName,
                "{}",
                AgentStatus.ACTIVE
        );
    }

    private static ActiveAgentRuntimeRow rowWithCode(
            String code
    ) {
        return new ActiveAgentRuntimeRow(
                AGENT_ID,
                TENANT_ID,
                code,
                SYSTEM_PROMPT,
                AgentModelProvider.OPENAI,
                MODEL_NAME,
                "{}",
                AgentStatus.ACTIVE
        );
    }
}
