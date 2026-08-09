package com.nexusagent.agent.internal;

import com.nexusagent.agent.api.ActiveAgentReference;
import com.nexusagent.agent.api.AgentNotFoundException;
import com.nexusagent.agent.domain.AgentStatus;
import com.nexusagent.agent.internal.persistence.ActiveAgentRow;
import com.nexusagent.agent.internal.persistence.AgentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultActiveAgentLookupTest {

    private static final long TENANT_ID = 202L;
    private static final long AGENT_ID = 901L;
    private static final String CODE = "support-agent";

    @Mock
    private AgentMapper agentMapper;

    private DefaultActiveAgentLookup lookup;

    @BeforeEach
    void setUp() {
        lookup = new DefaultActiveAgentLookup(
                agentMapper
        );
    }

    @Test
    void shouldNormalizeCodeAndReturnActiveReference() {
        ActiveAgentRow row = new ActiveAgentRow(
                AGENT_ID,
                TENANT_ID,
                CODE,
                AgentStatus.ACTIVE
        );

        when(agentMapper
                .findReferenceByTenantIdAndCodeAndStatus(
                        TENANT_ID,
                        CODE,
                        AgentStatus.ACTIVE
                ))
                .thenReturn(Optional.of(row));

        ActiveAgentReference result =
                lookup.requireActiveAgent(
                        TENANT_ID,
                        " SUPPORT-AGENT "
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
                () -> assertEquals(
                        CODE,
                        result.code()
                )
        );

        verify(agentMapper)
                .findReferenceByTenantIdAndCodeAndStatus(
                        TENANT_ID,
                        CODE,
                        AgentStatus.ACTIVE
                );
    }

    @Test
    void shouldHideMissingOrInactiveAgent() {
        when(agentMapper
                .findReferenceByTenantIdAndCodeAndStatus(
                        TENANT_ID,
                        CODE,
                        AgentStatus.ACTIVE
                ))
                .thenReturn(Optional.empty());

        AgentNotFoundException exception =
                assertThrows(
                        AgentNotFoundException.class,
                        () -> lookup.requireActiveAgent(
                                TENANT_ID,
                                CODE
                        )
                );

        assertEquals(
                "Agent not found",
                exception.getMessage()
        );
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void shouldRejectInvalidTenantId(
            long tenantId
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> lookup.requireActiveAgent(
                        tenantId,
                        CODE
                )
        );

        verifyNoInteractions(agentMapper);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            " ",
            "ab",
            "1agent",
            "agent_code"
    })
    void shouldRejectInvalidAgentCode(
            String agentCode
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> lookup.requireActiveAgent(
                        TENANT_ID,
                        agentCode
                )
        );

        verifyNoInteractions(agentMapper);
    }

    @ParameterizedTest
    @MethodSource("inconsistentRows")
    void shouldRejectInconsistentMapperRows(
            ActiveAgentRow row
    ) {
        when(agentMapper
                .findReferenceByTenantIdAndCodeAndStatus(
                        TENANT_ID,
                        CODE,
                        AgentStatus.ACTIVE
                ))
                .thenReturn(Optional.of(row));

        assertThrows(
                IllegalStateException.class,
                () -> lookup.requireActiveAgent(
                        TENANT_ID,
                        CODE
                )
        );
    }

    private static Stream<ActiveAgentRow>
    inconsistentRows() {
        return Stream.of(
                new ActiveAgentRow(
                        0L,
                        TENANT_ID,
                        CODE,
                        AgentStatus.ACTIVE
                ),
                new ActiveAgentRow(
                        AGENT_ID,
                        999L,
                        CODE,
                        AgentStatus.ACTIVE
                ),
                new ActiveAgentRow(
                        AGENT_ID,
                        TENANT_ID,
                        "other-agent",
                        AgentStatus.ACTIVE
                ),
                new ActiveAgentRow(
                        AGENT_ID,
                        TENANT_ID,
                        CODE,
                        AgentStatus.DISABLED
                ),
                new ActiveAgentRow(
                        AGENT_ID,
                        TENANT_ID,
                        CODE,
                        null
                )
        );
    }
}