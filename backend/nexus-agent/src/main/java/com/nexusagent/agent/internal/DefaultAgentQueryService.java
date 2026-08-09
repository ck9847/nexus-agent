package com.nexusagent.agent.internal;

import com.nexusagent.agent.api.AgentAdministrationForbiddenException;
import com.nexusagent.agent.api.AgentDetailResponse;
import com.nexusagent.agent.api.AgentNotFoundException;
import com.nexusagent.agent.api.AgentQueryService;
import com.nexusagent.agent.domain.AgentModelConfig;
import com.nexusagent.agent.internal.persistence.AgentDetailRow;
import com.nexusagent.agent.internal.persistence.AgentMapper;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultAgentQueryService
        implements AgentQueryService {

    private static final String ADMIN_ROLE = "ADMIN";

    private final CurrentActorProvider currentActorProvider;
    private final AgentMapper agentMapper;
    private final AgentModelConfigJsonCodec modelConfigJsonCodec;

    public DefaultAgentQueryService(
            CurrentActorProvider currentActorProvider,
            AgentMapper agentMapper,
            AgentModelConfigJsonCodec modelConfigJsonCodec
    ) {
        this.currentActorProvider = currentActorProvider;
        this.agentMapper = agentMapper;
        this.modelConfigJsonCodec = modelConfigJsonCodec;
    }

    @Override
    @Transactional(readOnly = true)
    public AgentDetailResponse getByCode(
            String agentCode
    ) {
        CurrentActor actor =
                currentActorProvider.requireCurrentActor();

        requireAdministrator(actor);

        String code =
                AgentCodeNormalizer.normalize(
                        agentCode
                );

        AgentDetailRow row = agentMapper
                .findDetailByTenantIdAndCode(
                        actor.tenantId(),
                        code
                )
                .orElseThrow(
                        AgentNotFoundException::new
                );

        verifyDetailRow(
                row,
                actor.tenantId(),
                code
        );

        AgentModelConfig modelConfig =
                modelConfigJsonCodec.decode(
                        row.modelConfigJson()
                );

        return new AgentDetailResponse(
                Long.toString(row.id()),
                row.code(),
                row.name(),
                row.description(),
                row.systemPrompt(),
                row.modelProvider(),
                row.modelName(),
                modelConfig,
                row.status(),
                Long.toString(
                        row.createdByUserId()
                ),
                row.version(),
                row.createdAt(),
                row.updatedAt()
        );
    }

    private static void requireAdministrator(
            CurrentActor actor
    ) {
        if (!actor.hasRole(ADMIN_ROLE)) {
            throw new AgentAdministrationForbiddenException();
        }
    }

    private static void verifyDetailRow(
            AgentDetailRow row,
            long expectedTenantId,
            String expectedCode
    ) {
        if (row.id() <= 0
                || row.tenantId() != expectedTenantId
                || !expectedCode.equals(row.code())
                || row.name() == null
                || row.name().isBlank()
                || row.systemPrompt() == null
                || row.systemPrompt().isBlank()
                || row.modelProvider() == null
                || row.modelName() == null
                || row.modelName().isBlank()
                || row.status() == null
                || row.createdByUserId() <= 0
                || row.version() < 0
                || row.createdAt() == null
                || row.updatedAt() == null
                || row.updatedAt().isBefore(
                row.createdAt()
        )) {
            throw new IllegalStateException(
                    "Agent detail query returned "
                            + "an inconsistent row"
            );
        }
    }
}