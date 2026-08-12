package com.nexusagent.agent.internal;

import com.nexusagent.agent.api.ActiveAgentRuntime;
import com.nexusagent.agent.api.AgentNotFoundException;
import com.nexusagent.agent.api.AgentRuntimeLookup;
import com.nexusagent.agent.domain.AgentStatus;
import com.nexusagent.agent.internal.persistence.ActiveAgentRuntimeRow;
import com.nexusagent.agent.internal.persistence.AgentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class DefaultAgentRuntimeLookup
        implements AgentRuntimeLookup {

    private final AgentMapper agentMapper;
    private final AgentModelConfigJsonCodec modelConfigCodec;

    public DefaultAgentRuntimeLookup(
            AgentMapper agentMapper,
            AgentModelConfigJsonCodec modelConfigCodec
    ) {
        this.agentMapper = Objects.requireNonNull(
                agentMapper,
                "agentMapper must not be null"
        );
        this.modelConfigCodec = Objects.requireNonNull(
                modelConfigCodec,
                "modelConfigCodec must not be null"
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ActiveAgentRuntime requireActiveAgent(
            long tenantId,
            long agentId
    ) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException(
                    "tenantId must be positive"
            );
        }

        if (agentId <= 0) {
            throw new IllegalArgumentException(
                    "agentId must be positive"
            );
        }

        ActiveAgentRuntimeRow row =
                Objects.requireNonNull(
                        agentMapper
                                .findActiveRuntimeByTenantIdAndId(
                                        tenantId,
                                        agentId
                                ),
                        "agentMapper must not return null"
                ).orElseThrow(AgentNotFoundException::new);

        validateRow(row, tenantId, agentId);

        return new ActiveAgentRuntime(
                row.id(),
                row.tenantId(),
                row.code(),
                row.systemPrompt(),
                row.modelProvider(),
                row.modelName(),
                modelConfigCodec.decode(
                        row.modelConfigJson()
                )
        );
    }

    private static void validateRow(
            ActiveAgentRuntimeRow row,
            long expectedTenantId,
            long expectedAgentId
    ) {
        if (row.id() != expectedAgentId
                || row.tenantId() != expectedTenantId) {
            throw new IllegalStateException(
                    "Agent mapper returned a row "
                            + "outside the requested scope"
            );
        }

        if (row.status() != AgentStatus.ACTIVE
                || row.modelProvider() == null
                || row.code() == null
                || row.code().isBlank()
                || row.systemPrompt() == null
                || row.systemPrompt().isBlank()
                || row.modelName() == null
                || row.modelName().isBlank()) {
            throw new IllegalStateException(
                    "Agent mapper returned "
                            + "an invalid runtime row"
            );
        }
    }
}