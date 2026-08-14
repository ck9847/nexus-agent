package com.nexusagent.conversation.internal;

import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.conversation.internal.persistence.MessageMapper;
import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatModelToolCall;
import com.nexusagent.model.api.ChatTokenUsage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;

@Service
public class DefaultCompleteConversationToolCallService
        implements CompleteConversationToolCallService {

    private static final String TOOL_NAME = "create_ticket";

    private final MessageMapper messageMapper;
    private final ConversationToolCallMessageJsonCodec
            toolCallJsonCodec;
    private final ConversationTurnMetadataJsonCodec metadataCodec;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public DefaultCompleteConversationToolCallService(
            MessageMapper messageMapper,
            ConversationToolCallMessageJsonCodec
                    toolCallJsonCodec,
            ConversationTurnMetadataJsonCodec metadataCodec,
            AuditLogWriter auditLogWriter,
            Clock clock
    ) {
        this.messageMapper = Objects.requireNonNull(
                messageMapper
        );
        this.toolCallJsonCodec = Objects.requireNonNull(
                toolCallJsonCodec
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
    public CompletedConversationToolCall complete(
            PreparedConversationTurn prepared,
            ChatModelToolCall toolCall,
            ChatTokenUsage usage,
            long toolExecutionId
    ) {
        Objects.requireNonNull(
                prepared,
                "prepared must not be null"
        );
        Objects.requireNonNull(
                toolCall,
                "toolCall must not be null"
        );
        Objects.requireNonNull(
                usage,
                "usage must not be null"
        );

        if (toolExecutionId <= 0) {
            throw new IllegalArgumentException(
                    "Tool execution ID must be positive"
            );
        }

        if (!TOOL_NAME.equals(toolCall.name())) {
            throw new IllegalArgumentException(
                    "Only create_ticket tool calls "
                            + "can be completed"
            );
        }

        Instant completedAt = clock.instant()
                .truncatedTo(ChronoUnit.MILLIS);

        if (completedAt.isBefore(prepared.preparedAt())) {
            throw new IllegalStateException(
                    "Completion time must not be "
                            + "before preparation time"
            );
        }

        String content =
                toolCallJsonCodec.encode(toolCall);

        String metadataJson = metadataCodec.encode(
                Map.of(
                        "messageKind", "TOOL_CALLS",
                        "provider", prepared.agent()
                                .modelProvider()
                                .name(),
                        "finishReason",
                        ChatModelFinishReason.TOOL_CALLS
                                .name(),
                        "toolCallId", toolCall.id(),
                        "toolName", toolCall.name(),
                        "toolExecutionId",
                        Long.toString(toolExecutionId),
                        "completedAt",
                        completedAt.toString()
                )
        );

        int affectedRows =
                messageMapper.completeAssistantToolCallMessage(
                        prepared.assistantMessageId(),
                        prepared.tenantId(),
                        prepared.conversationId(),
                        prepared.agent().agentId(),
                        prepared.assistantSequenceNo(),
                        content,
                        prepared.agent().modelName(),
                        usage.promptTokens(),
                        usage.completionTokens(),
                        metadataJson,
                        toolExecutionId,
                        toolCall.id(),
                        toolCall.name()
                );

        if (affectedRows != 1) {
            throw new IllegalStateException(
                    "Expected one assistant message "
                            + "to be completed"
            );
        }

        auditLogWriter.write(new AuditLogCommand(
                prepared.tenantId(),
                AuditActorType.AGENT,
                prepared.agent().agentId(),
                "CONVERSATION_TOOL_CALL_COMPLETED",
                "MESSAGE",
                prepared.assistantMessageId(),
                toolExecutionId,
                AuditResult.SUCCESS,
                null,
                null,
                null,
                Map.of(
                        "status",
                        "CREATING"
                ),
                Map.ofEntries(
                        Map.entry(
                                "conversationId",
                                Long.toString(
                                        prepared
                                                .conversationId()
                                )
                        ),
                        Map.entry(
                                "messageId",
                                Long.toString(
                                        prepared
                                                .assistantMessageId()
                                )
                        ),
                        Map.entry(
                                "sequenceNo",
                                prepared.assistantSequenceNo()
                        ),
                        Map.entry(
                                "status",
                                "COMPLETED"
                        ),
                        Map.entry(
                                "toolExecutionId",
                                Long.toString(toolExecutionId)
                        ),
                        Map.entry(
                                "toolCallId",
                                toolCall.id()
                        ),
                        Map.entry(
                                "toolName",
                                toolCall.name()
                        ),
                        Map.entry(
                                "promptTokens",
                                usage.promptTokens()
                        ),
                        Map.entry(
                                "completionTokens",
                                usage.completionTokens()
                        ),
                        Map.entry(
                                "completedAt",
                                completedAt.toString()
                        )
                ),
                null,
                null
        ));

        return new CompletedConversationToolCall(
                prepared.tenantId(),
                prepared.userId(),
                prepared.conversationId(),
                prepared.agent().agentId(),
                prepared.assistantMessageId(),
                prepared.assistantSequenceNo(),
                toolCall,
                toolExecutionId,
                prepared.agent().modelName(),
                usage,
                prepared.preparedAt(),
                completedAt
        );
    }
}
