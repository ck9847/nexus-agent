package com.nexusagent.agent.internal;

import com.nexusagent.agent.api.ActiveAgentLookup;
import com.nexusagent.agent.api.ActiveAgentReference;
import com.nexusagent.agent.api.AgentNotFoundException;
import com.nexusagent.agent.domain.AgentStatus;
import com.nexusagent.agent.internal.persistence.ActiveAgentRow;
import com.nexusagent.agent.internal.persistence.AgentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultActiveAgentLookup
        implements ActiveAgentLookup {

    private final AgentMapper agentMapper;

    public DefaultActiveAgentLookup(
            AgentMapper agentMapper
    ) {
        this.agentMapper = agentMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public ActiveAgentReference requireActiveAgent(
            long tenantId,
            String agentCode
    ) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException(
                    "tenantId must be positive"
            );
        }

        String code =
                AgentCodeNormalizer.normalize(
                        agentCode
                );

        ActiveAgentRow row = agentMapper
                .findReferenceByTenantIdAndCodeAndStatus(
                        tenantId,
                        code,
                        AgentStatus.ACTIVE
                )
                .orElseThrow(
                        AgentNotFoundException::new
                );

        verifyRow(
                row,
                tenantId,
                code
        );

        return new ActiveAgentReference(
                row.id(),
                row.tenantId(),
                row.code()
        );
    }

    private static void verifyRow(
            ActiveAgentRow row,
            long expectedTenantId,
            String expectedCode
    ) {
        if (row.id() <= 0
                || row.tenantId() != expectedTenantId
                || !expectedCode.equals(row.code())
                || row.status() != AgentStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Active Agent query returned "
                            + "an inconsistent row"
            );
        }
    }
}