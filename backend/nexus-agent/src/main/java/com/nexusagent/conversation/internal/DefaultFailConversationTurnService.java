package com.nexusagent.conversation.internal;

import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.conversation.domain.MessageStatus;
import com.nexusagent.conversation.internal.persistence.MessageMapper;
import com.nexusagent.model.api.ChatModelException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class DefaultFailConversationTurnService
        implements FailConversationTurnService {

    private static final String SAFE_ERROR_MESSAGE =
            "Chat model turn failed";

    private final MessageMapper messageMapper;
    private final ConversationTurnMetadataJsonCodec metadataCodec;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public DefaultFailConversationTurnService(
            MessageMapper messageMapper,
            ConversationTurnMetadataJsonCodec metadataCodec,
            AuditLogWriter auditLogWriter,
            Clock clock
    ) {
        this.messageMapper = Objects.requireNonNull(
                messageMapper
        );
        this.metadataCodec = Objects.requireNonNull(
                metadataCodec
        );
        this.auditLogWriter = Objects.requireNonNull(
                auditLogWriter
        );
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(
            AssistantMessageCompletionTarget target,
            ChatModelException failure
    ) {
        Objects.requireNonNull(
                target,
                "target must not be null"
        );
        Objects.requireNonNull(
                failure,
                "failure must not be null"
        );

        Instant failedAt = clock.instant()
                .truncatedTo(ChronoUnit.MILLIS);

        if (failedAt.isBefore(target.preparedAt())) {
            throw new IllegalStateException(
                    "Failure time must not be "
                            + "before preparation time"
            );
        }

        String errorCode =
                "CHAT_MODEL_"
                        + failure.category().name();

        Map<String, Object> metadata =
                new LinkedHashMap<>();

        metadata.put(
                "provider",
                target.agent()
                        .modelProvider()
                        .name()
        );
        metadata.put("errorCode", errorCode);
        metadata.put(
                "retryable",
                failure.retryable()
        );

        if (failure.httpStatus() != null) {
            metadata.put(
                    "providerStatus",
                    failure.httpStatus()
            );
        }

        metadata.put(
                "failedAt",
                failedAt.toString()
        );

        String metadataJson =
                metadataCodec.encode(
                        Map.copyOf(metadata)
                );

        int rows = messageMapper.failAssistantMessage(
                target.assistantMessageId(),
                target.tenantId(),
                target.conversationId(),
                target.assistantSequenceNo(),
                target.agent().modelName(),
                metadataJson
        );

        if (rows != 1) {
            throw new IllegalStateException(
                    "Expected one assistant message "
                            + "to be failed"
            );
        }

        Map<String, Object> afterData =
                new LinkedHashMap<>();

        afterData.put(
                "conversationId",
                Long.toString(target.conversationId())
        );
        afterData.put(
                "messageId",
                Long.toString(
                        target.assistantMessageId()
                )
        );
        afterData.put(
                "sequenceNo",
                target.assistantSequenceNo()
        );
        afterData.put(
                "status",
                MessageStatus.FAILED.name()
        );
        afterData.put(
                "modelProvider",
                target.agent()
                        .modelProvider()
                        .name()
        );
        afterData.put(
                "modelName",
                target.agent().modelName()
        );
        afterData.put("errorCode", errorCode);
        afterData.put(
                "retryable",
                failure.retryable()
        );

        if (failure.httpStatus() != null) {
            afterData.put(
                    "providerStatus",
                    failure.httpStatus()
            );
        }

        afterData.put(
                "failedAt",
                failedAt.toString()
        );

        auditLogWriter.write(new AuditLogCommand(
                target.tenantId(),
                AuditActorType.AGENT,
                target.agent().agentId(),
                "CONVERSATION_TURN_FAILED",
                "MESSAGE",
                target.assistantMessageId(),
                null,
                AuditResult.FAILURE,
                null,
                null,
                null,
                Map.of(
                        "status",
                        MessageStatus.CREATING.name()
                ),
                Map.copyOf(afterData),
                errorCode,
                SAFE_ERROR_MESSAGE
        ));
    }
}
