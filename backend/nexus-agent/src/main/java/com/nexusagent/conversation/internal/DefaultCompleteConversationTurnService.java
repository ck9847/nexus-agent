package com.nexusagent.conversation.internal;

import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.conversation.domain.MessageStatus;
import com.nexusagent.conversation.internal.persistence.MessageMapper;
import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatTokenUsage;
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
public class DefaultCompleteConversationTurnService
        implements CompleteConversationTurnService {

    private final MessageMapper messageMapper;
    private final ConversationTurnMetadataJsonCodec metadataCodec;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public DefaultCompleteConversationTurnService(
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
    public CompletedConversationTurn complete(
            AssistantMessageCompletionTarget target,
            String assistantContent,
            ChatModelFinishReason finishReason,
            ChatTokenUsage usage
    ) {
        Objects.requireNonNull(
                target,
                "target must not be null"
        );

        String content =
                ConversationAssistantContentValidator
                        .requireValid(assistantContent);

        Objects.requireNonNull(
                finishReason,
                "finishReason must not be null"
        );
        Objects.requireNonNull(
                usage,
                "usage must not be null"
        );

        Instant completedAt = clock.instant()
                .truncatedTo(ChronoUnit.MILLIS);

        if (completedAt.isBefore(target.preparedAt())) {
            throw new IllegalStateException(
                    "Completion time must not be "
                            + "before preparation time"
            );
        }

        Map<String, Object> metadata =
                new LinkedHashMap<>();

        metadata.put(
                "provider",
                target.agent()
                        .modelProvider()
                        .name()
        );
        metadata.put(
                "finishReason",
                finishReason.name()
        );
        metadata.put(
                "completedAt",
                completedAt.toString()
        );

        String metadataJson =
                metadataCodec.encode(
                        Map.copyOf(metadata)
                );

        int rows = messageMapper.completeAssistantMessage(
                target.assistantMessageId(),
                target.tenantId(),
                target.conversationId(),
                target.assistantSequenceNo(),
                content,
                target.agent().modelName(),
                usage.promptTokens(),
                usage.completionTokens(),
                metadataJson
        );

        if (rows != 1) {
            throw new IllegalStateException(
                    "Expected one assistant message "
                            + "to be completed"
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
                MessageStatus.COMPLETED.name()
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
        afterData.put(
                "finishReason",
                finishReason.name()
        );
        afterData.put(
                "promptTokens",
                usage.promptTokens()
        );
        afterData.put(
                "completionTokens",
                usage.completionTokens()
        );
        afterData.put(
                "completedAt",
                completedAt.toString()
        );

        auditLogWriter.write(new AuditLogCommand(
                target.tenantId(),
                AuditActorType.AGENT,
                target.agent().agentId(),
                "CONVERSATION_TURN_COMPLETED",
                "MESSAGE",
                target.assistantMessageId(),
                null,
                AuditResult.SUCCESS,
                null,
                null,
                null,
                Map.of(
                        "status",
                        MessageStatus.CREATING.name()
                ),
                Map.copyOf(afterData),
                null,
                null
        ));

        return new CompletedConversationTurn(
                target.tenantId(),
                target.userId(),
                target.conversationId(),
                target.agent().agentId(),
                target.assistantMessageId(),
                target.assistantSequenceNo(),
                content,
                target.agent().modelName(),
                finishReason,
                usage,
                target.preparedAt(),
                completedAt
        );
    }
}
