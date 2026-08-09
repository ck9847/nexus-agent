package com.nexusagent.agent.internal;

import com.nexusagent.agent.api.AgentAdministrationForbiddenException;
import com.nexusagent.agent.api.AgentCodeAlreadyExistsException;
import com.nexusagent.agent.api.CreateAgentRequest;
import com.nexusagent.agent.api.CreateAgentResponse;
import com.nexusagent.agent.api.CreateAgentService;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;


@Service
public class DefaultCreateAgentService
        implements CreateAgentService {

    private static final String ADMIN_ROLE = "ADMIN";

    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_DESCRIPTION_LENGTH = 500;
    private static final int MAX_SYSTEM_PROMPT_LENGTH = 50_000;
    private static final int MAX_MODEL_NAME_LENGTH = 128;
    private static final int MAX_OUTPUT_TOKENS = 131_072;

    private static final BigDecimal ZERO =
            new BigDecimal("0.0");
    private static final BigDecimal ONE =
            new BigDecimal("1.0");
    private static final BigDecimal TWO =
            new BigDecimal("2.0");

    private final CurrentActorProvider currentActorProvider;
    private final IdGenerator idGenerator;
    private final AgentMapper agentMapper;
    private final AgentModelConfigJsonCodec modelConfigJsonCodec;
    private final AuditLogWriter auditLogWriter;

    public DefaultCreateAgentService(
            CurrentActorProvider currentActorProvider,
            IdGenerator idGenerator,
            AgentMapper agentMapper,
            AgentModelConfigJsonCodec modelConfigJsonCodec,
            AuditLogWriter auditLogWriter
    ) {
        this.currentActorProvider = currentActorProvider;
        this.idGenerator = idGenerator;
        this.agentMapper = agentMapper;
        this.modelConfigJsonCodec = modelConfigJsonCodec;
        this.auditLogWriter = auditLogWriter;
    }

    @Override
    @Transactional
    public CreateAgentResponse create(
            CreateAgentRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        CurrentActor actor =
                currentActorProvider.requireCurrentActor();

        if (!actor.hasRole(ADMIN_ROLE)) {
            throw new AgentAdministrationForbiddenException();
        }

        String code = AgentCodeNormalizer.normalize(
                request.code()
        );

        String name = normalizeRequired(
                request.name(),
                "name",
                MAX_NAME_LENGTH
        );

        String description = normalizeOptional(
                request.description(),
                "description",
                MAX_DESCRIPTION_LENGTH
        );

        String systemPrompt = normalizeRequired(
                request.systemPrompt(),
                "systemPrompt",
                MAX_SYSTEM_PROMPT_LENGTH
        );

        AgentModelProvider modelProvider =
                requireModelProvider(
                        request.modelProvider()
                );

        String modelName = normalizeRequired(
                request.modelName(),
                "modelName",
                MAX_MODEL_NAME_LENGTH
        );

        AgentModelConfig modelConfig =
                request.modelConfig();

        validateModelConfig(modelConfig);

        if (agentMapper.existsByTenantIdAndCode(
                actor.tenantId(),
                code
        )) {
            throw new AgentCodeAlreadyExistsException(
                    code
            );
        }

        String modelConfigJson =
                modelConfigJsonCodec.encode(
                        modelConfig
                );

        long agentId = idGenerator.nextId();

        AgentRow row = new AgentRow(
                agentId,
                actor.tenantId(),
                code,
                name,
                description,
                systemPrompt,
                modelProvider,
                modelName,
                modelConfigJson,
                AgentStatus.DRAFT,
                actor.userId(),
                0
        );

        int affectedRows;

        try {
            affectedRows = agentMapper.insert(row);
        } catch (DuplicateKeyException exception) {
            throw new AgentCodeAlreadyExistsException(
                    code,
                    exception
            );
        }

        if (affectedRows != 1) {
            throw new IllegalStateException(
                    "Expected one agent row to be inserted"
            );
        }

        auditLogWriter.write(new AuditLogCommand(
                actor.tenantId(),
                AuditActorType.USER,
                actor.userId(),
                "AGENT_CREATED",
                "AGENT",
                agentId,
                null,
                AuditResult.SUCCESS,
                null,
                null,
                null,
                null,
                Map.of(
                        "code", code,
                        "name", name,
                        "modelProvider",
                        modelProvider.name(),
                        "modelName", modelName,
                        "status",
                        AgentStatus.DRAFT.name(),
                        "version", 0
                ),
                null,
                null
        ));

        return new CreateAgentResponse(
                Long.toString(agentId),
                code,
                AgentStatus.DRAFT,
                0
        );
    }

    private static String normalizeRequired(
            String value,
            String fieldName,
            int maxLength
    ) {
        if (value == null) {
            throw new IllegalArgumentException(
                    fieldName + " must not be null"
            );
        }

        String normalized = value.trim();

        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank"
            );
        }

        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    fieldName + " must not exceed "
                            + maxLength + " characters"
            );
        }

        return normalized;
    }

    private static String normalizeOptional(
            String value,
            String fieldName,
            int maxLength
    ) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();

        if (normalized.isBlank()) {
            return null;
        }

        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    fieldName + " must not exceed "
                            + maxLength + " characters"
            );
        }

        return normalized;
    }

    private static AgentModelProvider requireModelProvider(
            AgentModelProvider modelProvider
    ) {
        if (modelProvider == null) {
            throw new IllegalArgumentException(
                    "modelProvider must not be null"
            );
        }

        return modelProvider;
    }

    private static void validateModelConfig(
            AgentModelConfig config
    ) {
        if (config == null) {
            return;
        }

        validateDecimalRange(
                config.temperature(),
                "temperature",
                ZERO,
                TWO
        );

        validateDecimalRange(
                config.topP(),
                "topP",
                ZERO,
                ONE
        );

        Integer maxOutputTokens =
                config.maxOutputTokens();

        if (maxOutputTokens != null
                && (maxOutputTokens < 1
                || maxOutputTokens > MAX_OUTPUT_TOKENS)) {
            throw new IllegalArgumentException(
                    "maxOutputTokens must be between "
                            + "1 and " + MAX_OUTPUT_TOKENS
            );
        }
    }

    private static void validateDecimalRange(
            BigDecimal value,
            String fieldName,
            BigDecimal minimum,
            BigDecimal maximum
    ) {
        if (value == null) {
            return;
        }

        if (value.compareTo(minimum) < 0
                || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    fieldName + " must be between "
                            + minimum + " and " + maximum
            );
        }
    }
}