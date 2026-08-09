package com.nexusagent.agent.internal;

import com.nexusagent.agent.api.AgentAdministrationForbiddenException;
import com.nexusagent.agent.api.AgentNotFoundException;
import com.nexusagent.agent.api.ChangeAgentStatusRequest;
import com.nexusagent.agent.api.ChangeAgentStatusResponse;
import com.nexusagent.agent.domain.AgentStatus;
import com.nexusagent.agent.domain.AgentVersionConflictException;
import com.nexusagent.agent.domain.InvalidAgentStatusTransitionException;
import com.nexusagent.agent.internal.persistence.AgentMapper;
import com.nexusagent.agent.internal.persistence.AgentStatusRow;
import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultChangeAgentStatusServiceTest {

    private static final CurrentActor ADMIN =
            new CurrentActor(
                    101L,
                    202L,
                    "admin",
                    Set.of("ADMIN")
            );

    private static final Instant BEFORE_TIME =
            Instant.parse(
                    "2026-08-09T01:00:00Z"
            );

    private static final Instant AFTER_TIME =
            Instant.parse(
                    "2026-08-09T02:00:00Z"
            );

    @Mock
    private CurrentActorProvider currentActorProvider;

    @Mock
    private AgentMapper agentMapper;

    @Mock
    private AuditLogWriter auditLogWriter;

    private DefaultChangeAgentStatusService service;

    @BeforeEach
    void setUp() {
        service =
                new DefaultChangeAgentStatusService(
                        currentActorProvider,
                        agentMapper,
                        auditLogWriter
                );
    }

    @Test
    void shouldActivateDraftAgentAndWriteAudit() {
        AgentStatusRow before = beforeRow();
        AgentStatusRow after = afterRow();

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ADMIN);

        when(agentMapper.findStatusByTenantIdAndCode(
                202L,
                "support-agent"
        )).thenReturn(
                Optional.of(before)
        ).thenReturn(
                Optional.of(after)
        );

        when(agentMapper.updateStatus(
                202L,
                "support-agent",
                AgentStatus.DRAFT,
                AgentStatus.ACTIVE,
                0
        )).thenReturn(1);

        ChangeAgentStatusResponse response =
                service.changeStatus(
                        " SUPPORT-AGENT ",
                        new ChangeAgentStatusRequest(
                                AgentStatus.ACTIVE,
                                0
                        )
                );

        ArgumentCaptor<AuditLogCommand> auditCaptor =
                ArgumentCaptor.forClass(
                        AuditLogCommand.class
                );

        verify(auditLogWriter).write(
                auditCaptor.capture()
        );

        AuditLogCommand audit =
                auditCaptor.getValue();

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
                        response.previousStatus()
                ),
                () -> assertEquals(
                        AgentStatus.ACTIVE,
                        response.currentStatus()
                ),
                () -> assertEquals(
                        1,
                        response.version()
                ),
                () -> assertEquals(
                        AFTER_TIME,
                        response.updatedAt()
                )
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
                        "AGENT_STATUS_CHANGED",
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
                () -> assertEquals(
                        AuditResult.SUCCESS,
                        audit.result()
                ),
                () -> assertEquals(
                        Map.of(
                                "code",
                                "support-agent",
                                "status",
                                "DRAFT",
                                "version",
                                0
                        ),
                        audit.beforeData()
                ),
                () -> assertEquals(
                        Map.of(
                                "code",
                                "support-agent",
                                "status",
                                "ACTIVE",
                                "version",
                                1
                        ),
                        audit.afterData()
                )
        );

        verify(agentMapper).updateStatus(
                202L,
                "support-agent",
                AgentStatus.DRAFT,
                AgentStatus.ACTIVE,
                0
        );

        verify(agentMapper, times(2))
                .findStatusByTenantIdAndCode(
                        202L,
                        "support-agent"
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

        assertThrows(
                AgentAdministrationForbiddenException.class,
                () -> service.changeStatus(
                        null,
                        new ChangeAgentStatusRequest(
                                null,
                                null
                        )
                )
        );

        verifyNoInteractions(
                agentMapper,
                auditLogWriter
        );
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void shouldRejectInvalidRequestFieldsBeforeQuery(
            ChangeAgentStatusRequest invalidRequest
    ) {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ADMIN);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.changeStatus(
                        "support-agent",
                        invalidRequest
                )
        );

        verifyNoInteractions(
                agentMapper,
                auditLogWriter
        );
    }

    @Test
    void shouldThrowNotFoundWhenAgentDoesNotExist() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ADMIN);

        when(agentMapper.findStatusByTenantIdAndCode(
                202L,
                "missing-agent"
        )).thenReturn(Optional.empty());

        assertThrows(
                AgentNotFoundException.class,
                () -> service.changeStatus(
                        "missing-agent",
                        new ChangeAgentStatusRequest(
                                AgentStatus.ACTIVE,
                                0
                        )
                )
        );

        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldRejectStaleExpectedVersion() {
        AgentStatusRow before =
                new AgentStatusRow(
                        901L,
                        202L,
                        "support-agent",
                        AgentStatus.DRAFT,
                        1,
                        BEFORE_TIME
                );

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ADMIN);

        when(agentMapper.findStatusByTenantIdAndCode(
                202L,
                "support-agent"
        )).thenReturn(Optional.of(before));

        assertThrows(
                AgentVersionConflictException.class,
                () -> service.changeStatus(
                        "support-agent",
                        new ChangeAgentStatusRequest(
                                AgentStatus.ACTIVE,
                                0
                        )
                )
        );

        verify(agentMapper, never()).updateStatus(
                anyLong(),
                anyString(),
                any(AgentStatus.class),
                any(AgentStatus.class),
                anyInt()
        );

        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldRejectInvalidStatusTransition() {
        AgentStatusRow before = beforeRow();

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ADMIN);

        when(agentMapper.findStatusByTenantIdAndCode(
                202L,
                "support-agent"
        )).thenReturn(Optional.of(before));

        assertThrows(
                InvalidAgentStatusTransitionException.class,
                () -> service.changeStatus(
                        "support-agent",
                        new ChangeAgentStatusRequest(
                                AgentStatus.DISABLED,
                                0
                        )
                )
        );

        verify(agentMapper, never()).updateStatus(
                anyLong(),
                anyString(),
                any(AgentStatus.class),
                any(AgentStatus.class),
                anyInt()
        );

        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldTreatConcurrentCasFailureAsVersionConflict() {
        AgentStatusRow before = beforeRow();

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ADMIN);

        when(agentMapper.findStatusByTenantIdAndCode(
                202L,
                "support-agent"
        )).thenReturn(Optional.of(before));

        when(agentMapper.updateStatus(
                202L,
                "support-agent",
                AgentStatus.DRAFT,
                AgentStatus.ACTIVE,
                0
        )).thenReturn(0);

        assertThrows(
                AgentVersionConflictException.class,
                () -> service.changeStatus(
                        "support-agent",
                        new ChangeAgentStatusRequest(
                                AgentStatus.ACTIVE,
                                0
                        )
                )
        );

        verify(agentMapper, times(1))
                .findStatusByTenantIdAndCode(
                        202L,
                        "support-agent"
                );

        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldRejectInconsistentBeforeRow() {
        AgentStatusRow wrongTenant =
                new AgentStatusRow(
                        901L,
                        999L,
                        "support-agent",
                        AgentStatus.DRAFT,
                        0,
                        BEFORE_TIME
                );

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ADMIN);

        when(agentMapper.findStatusByTenantIdAndCode(
                202L,
                "support-agent"
        )).thenReturn(Optional.of(wrongTenant));

        assertThrows(
                IllegalStateException.class,
                () -> service.changeStatus(
                        "support-agent",
                        new ChangeAgentStatusRequest(
                                AgentStatus.ACTIVE,
                                0
                        )
                )
        );

        verify(agentMapper, never()).updateStatus(
                anyLong(),
                anyString(),
                any(AgentStatus.class),
                any(AgentStatus.class),
                anyInt()
        );

        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldRejectInconsistentAfterRow() {
        AgentStatusRow before = beforeRow();

        AgentStatusRow wrongVersionAfter =
                new AgentStatusRow(
                        901L,
                        202L,
                        "support-agent",
                        AgentStatus.ACTIVE,
                        2,
                        AFTER_TIME
                );

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ADMIN);

        when(agentMapper.findStatusByTenantIdAndCode(
                202L,
                "support-agent"
        )).thenReturn(
                Optional.of(before)
        ).thenReturn(
                Optional.of(wrongVersionAfter)
        );

        when(agentMapper.updateStatus(
                202L,
                "support-agent",
                AgentStatus.DRAFT,
                AgentStatus.ACTIVE,
                0
        )).thenReturn(1);

        assertThrows(
                IllegalStateException.class,
                () -> service.changeStatus(
                        "support-agent",
                        new ChangeAgentStatusRequest(
                                AgentStatus.ACTIVE,
                                0
                        )
                )
        );

        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldPropagateAuditFailure() {
        AgentStatusRow before = beforeRow();
        AgentStatusRow after = afterRow();

        IllegalStateException auditFailure =
                new IllegalStateException(
                        "Simulated audit failure"
                );

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ADMIN);

        when(agentMapper.findStatusByTenantIdAndCode(
                202L,
                "support-agent"
        )).thenReturn(
                Optional.of(before)
        ).thenReturn(
                Optional.of(after)
        );

        when(agentMapper.updateStatus(
                202L,
                "support-agent",
                AgentStatus.DRAFT,
                AgentStatus.ACTIVE,
                0
        )).thenReturn(1);

        doThrow(auditFailure)
                .when(auditLogWriter)
                .write(any(AuditLogCommand.class));

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.changeStatus(
                                "support-agent",
                                new ChangeAgentStatusRequest(
                                        AgentStatus.ACTIVE,
                                        0
                                )
                        )
                );

        assertSame(
                auditFailure,
                thrown
        );
    }

    private static Stream<ChangeAgentStatusRequest>
    invalidRequests() {
        return Stream.of(
                new ChangeAgentStatusRequest(
                        null,
                        0
                ),
                new ChangeAgentStatusRequest(
                        AgentStatus.ACTIVE,
                        null
                ),
                new ChangeAgentStatusRequest(
                        AgentStatus.ACTIVE,
                        -1
                )
        );
    }

    private static AgentStatusRow beforeRow() {
        return new AgentStatusRow(
                901L,
                202L,
                "support-agent",
                AgentStatus.DRAFT,
                0,
                BEFORE_TIME
        );
    }

    private static AgentStatusRow afterRow() {
        return new AgentStatusRow(
                901L,
                202L,
                "support-agent",
                AgentStatus.ACTIVE,
                1,
                AFTER_TIME
        );
    }
}