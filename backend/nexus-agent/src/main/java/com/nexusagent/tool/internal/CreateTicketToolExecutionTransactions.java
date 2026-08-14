package com.nexusagent.tool.internal;

import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.common.id.IdGenerator;
import com.nexusagent.conversation.api.ConversationNotActiveException;
import com.nexusagent.conversation.api.ConversationNotFoundException;
import com.nexusagent.conversation.domain.ConversationStatus;
import com.nexusagent.conversation.domain.MessageContentType;
import com.nexusagent.conversation.domain.MessageRole;
import com.nexusagent.conversation.domain.MessageStatus;
import com.nexusagent.conversation.internal.ConversationTurnMetadataJsonCodec;
import com.nexusagent.conversation.internal.persistence.ConversationMapper;
import com.nexusagent.conversation.internal.persistence.ConversationTurnStateRow;
import com.nexusagent.conversation.internal.persistence.MessageMapper;
import com.nexusagent.conversation.internal.persistence.MessageRow;
import com.nexusagent.ticket.api.CreateTicketResponse;
import com.nexusagent.tool.api.ToolExecutionApprovalRequiredException;
import com.nexusagent.tool.api.ToolExecutionInProgressException;
import com.nexusagent.tool.api.ToolExecutionNotFoundException;
import com.nexusagent.tool.api.ToolExecutionTerminalStateException;
import com.nexusagent.tool.domain.ToolExecutionStatus;
import com.nexusagent.tool.internal.persistence.ToolExecutionMapper;
import com.nexusagent.tool.internal.persistence.ToolExecutionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;

@Service
public class CreateTicketToolExecutionTransactions {

    private static final String TOOL_NAME = "create_ticket";

    private final ConversationMapper conversationMapper;
    private final ToolExecutionMapper toolExecutionMapper;
    private final CreateTicketToolJsonCodec ticketToolJsonCodec;
    private final CreateTicketAgentTool createTicketAgentTool;
    private final MessageMapper messageMapper;
    private final ConversationTurnMetadataJsonCodec metadataCodec;
    private final IdGenerator idGenerator;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public CreateTicketToolExecutionTransactions(
            ConversationMapper conversationMapper,
            ToolExecutionMapper toolExecutionMapper,
            CreateTicketToolJsonCodec ticketToolJsonCodec,
            CreateTicketAgentTool createTicketAgentTool,
            MessageMapper messageMapper,
            ConversationTurnMetadataJsonCodec metadataCodec,
            IdGenerator idGenerator,
            AuditLogWriter auditLogWriter,
            Clock clock
    ) {
        this.conversationMapper = Objects.requireNonNull(
                conversationMapper
        );
        this.toolExecutionMapper = Objects.requireNonNull(
                toolExecutionMapper
        );
        this.ticketToolJsonCodec = Objects.requireNonNull(
                ticketToolJsonCodec
        );
        this.createTicketAgentTool = Objects.requireNonNull(
                createTicketAgentTool
        );
        this.messageMapper = Objects.requireNonNull(
                messageMapper
        );
        this.metadataCodec = Objects.requireNonNull(
                metadataCodec
        );
        this.idGenerator = Objects.requireNonNull(
                idGenerator
        );
        this.auditLogWriter = Objects.requireNonNull(
                auditLogWriter
        );
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaimedCreateTicketToolExecution claim(
            AgentToolExecutionContext context
    ) {
        Objects.requireNonNull(
                context,
                "context must not be null"
        );

        Instant startedAt = clock.instant()
                .truncatedTo(ChronoUnit.MILLIS);

        ConversationTurnStateRow conversation =
                lockConversation(context);

        ToolExecutionRow execution =
                lockExecution(context);

        requireMatchingExecution(execution, context);

        return switch (execution.status()) {
            case PENDING -> {
                requireActiveConversation(
                        conversation,
                        context
                );
                yield claimPending(
                        context,
                        execution,
                        startedAt
                );
            }

            case RUNNING ->
                    throw new ToolExecutionInProgressException();

            case WAITING_APPROVAL ->
                    throw new ToolExecutionApprovalRequiredException();

            case SUCCEEDED ->
                    replaySucceeded(
                            context,
                            conversation,
                            execution
                    );

            case FAILED, CANCELLED ->
                    throw new ToolExecutionTerminalStateException();
        };
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExecuteCreateTicketToolResult succeed(
            ClaimedCreateTicketToolExecution claim
    ) {
        Objects.requireNonNull(
                claim,
                "claim must not be null"
        );

        AgentToolExecutionContext context = claim.context();

        ConversationTurnStateRow conversation =
                lockConversation(context);

        requireActiveConversation(conversation, context);

        ToolExecutionRow execution =
                lockExecution(context);

        requireMatchingExecution(execution, context);

        if (execution.status() != ToolExecutionStatus.RUNNING) {
            throw new IllegalStateException(
                    "Tool execution is not running"
            );
        }

        ToolCallRequestMessageRow requestMessage =
                Objects.requireNonNull(
                        messageMapper
                                .findCompletedToolCallRequestForUpdate(
                                        context
                                                .requestMessageId(),
                                        context.tenantId(),
                                        context
                                                .conversationId()
                                ),
                        "messageMapper must not return null"
                ).orElseThrow(() -> new IllegalStateException(
                        "Completed tool call request "
                                + "message is missing"
                ));

        if (conversation.nextMessageSequence()
                != requestMessage.sequenceNo() + 1) {
            throw new IllegalStateException(
                    "Conversation sequence must immediately "
                            + "follow the tool call request"
            );
        }

        Instant completedAt = clock.instant()
                .truncatedTo(ChronoUnit.MILLIS);

        if (completedAt.isBefore(claim.startedAt())) {
            throw new IllegalStateException(
                    "Completion time must not be "
                            + "before claim time"
            );
        }

        if (conversation.nextMessageSequence()
                > Long.MAX_VALUE - 2) {
            throw new IllegalStateException(
                    "Conversation message sequence is exhausted"
            );
        }

        if (conversation.version() == Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "Conversation version is exhausted"
            );
        }

        CreateTicketResponse ticket =
                createTicketAgentTool.execute(
                        context,
                        claim.arguments()
                );

        long resultEntityId;

        try {
            resultEntityId =
                    Long.parseLong(ticket.ticketId());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "Ticket service returned "
                            + "an invalid ticket id"
            );
        }

        if (resultEntityId <= 0) {
            throw new IllegalStateException(
                    "Ticket service returned "
                            + "an invalid ticket id"
            );
        }

        long toolSequence = conversation.nextMessageSequence();
        long finalAssistantSequence = toolSequence + 1;
        int newConversationVersion =
                conversation.version() + 1;

        long toolMessageId = idGenerator.nextId();
        long finalAssistantMessageId = idGenerator.nextId();

        if (toolMessageId <= 0
                || finalAssistantMessageId <= 0) {
            throw new IllegalStateException(
                    "Generated message IDs must be positive"
            );
        }

        if (toolMessageId == finalAssistantMessageId) {
            throw new IllegalStateException(
                    "Generated message IDs must be distinct"
            );
        }

        CreateTicketToolOutput output =
                new CreateTicketToolOutput(
                        ticket.ticketId(),
                        ticket.ticketNo(),
                        ticket.status()
                );

        String outputJson =
                ticketToolJsonCodec.encodeOutput(output);

        String toolMetadataJson = metadataCodec.encode(
                Map.of(
                        "toolExecutionId",
                        Long.toString(
                                context.toolExecutionId()
                        ),
                        "toolCallId",
                        context.toolCallId(),
                        "toolName",
                        TOOL_NAME
                )
        );

        MessageRow toolMessage = new MessageRow(
                toolMessageId,
                context.tenantId(),
                context.conversationId(),
                toolSequence,
                MessageRole.TOOL,
                outputJson,
                MessageContentType.JSON,
                MessageStatus.COMPLETED,
                null,
                null,
                null,
                toolMetadataJson,
                completedAt
        );

        String continuationMetadataJson = metadataCodec.encode(
                Map.of(
                        "messageKind", "TOOL_CONTINUATION",
                        "toolExecutionId",
                        Long.toString(
                                context.toolExecutionId()
                        ),
                        "toolCallId",
                        context.toolCallId(),
                        "resultMessageId",
                        Long.toString(toolMessageId),
                        "conversationVersion",
                        newConversationVersion
                )
        );

        MessageRow finalAssistantPlaceholder =
                new MessageRow(
                        finalAssistantMessageId,
                        context.tenantId(),
                        context.conversationId(),
                        finalAssistantSequence,
                        MessageRole.ASSISTANT,
                        "",
                        MessageContentType.TEXT,
                        MessageStatus.CREATING,
                        requestMessage.modelName(),
                        null,
                        null,
                        continuationMetadataJson,
                        completedAt
                );

        requireOneRow(
                messageMapper.insert(toolMessage),
                "Expected one TOOL message "
                        + "to be inserted"
        );

        requireOneRow(
                messageMapper.insert(
                        finalAssistantPlaceholder
                ),
                "Expected one continuation "
                        + "assistant message to be inserted"
        );

        long durationMs = Duration.between(
                claim.startedAt(),
                completedAt
        ).toMillis();

        requireOneRow(
                conversationMapper.advanceForToolContinuation(
                        context.conversationId(),
                        context.tenantId(),
                        context.requesterUserId(),
                        conversation.nextMessageSequence(),
                        conversation.version(),
                        completedAt
                ),
                "Expected conversation sequence "
                        + "to be advanced"
        );

        requireOneRow(
                toolExecutionMapper.markSucceeded(
                        context.tenantId(),
                        context.toolExecutionId(),
                        context.conversationId(),
                        context.agentId(),
                        context.requestMessageId(),
                        context.toolCallId(),
                        toolMessageId,
                        outputJson,
                        resultEntityId,
                        completedAt,
                        durationMs
                ),
                "Expected tool execution "
                        + "to be marked succeeded"
        );

        auditLogWriter.write(new AuditLogCommand(
                context.tenantId(),
                AuditActorType.AGENT,
                context.agentId(),
                "TOOL_MESSAGE_WRITTEN",
                "MESSAGE",
                toolMessageId,
                context.toolExecutionId(),
                AuditResult.SUCCESS,
                null,
                null,
                null,
                null,
                Map.of(
                        "messageId",
                        Long.toString(toolMessageId),
                        "conversationId",
                        Long.toString(
                                context.conversationId()
                        ),
                        "sequenceNo",
                        toolSequence,
                        "toolExecutionId",
                        Long.toString(
                                context.toolExecutionId()
                        )
                ),
                null,
                null
        ));

        auditLogWriter.write(new AuditLogCommand(
                context.tenantId(),
                AuditActorType.AGENT,
                context.agentId(),
                "CONVERSATION_TOOL_CONTINUATION_PREPARED",
                "MESSAGE",
                finalAssistantMessageId,
                context.toolExecutionId(),
                AuditResult.SUCCESS,
                null,
                null,
                null,
                null,
                Map.of(
                        "conversationId",
                        Long.toString(
                                context.conversationId()
                        ),
                        "messageId",
                        Long.toString(
                                finalAssistantMessageId
                        ),
                        "sequenceNo",
                        finalAssistantSequence,
                        "status",
                        MessageStatus.CREATING.name(),
                        "toolExecutionId",
                        Long.toString(
                                context.toolExecutionId()
                        ),
                        "resultMessageId",
                        Long.toString(toolMessageId),
                        "conversationVersion",
                        newConversationVersion,
                        "preparedAt",
                        completedAt.toString()
                ),
                null,
                null
        ));

        auditLogWriter.write(new AuditLogCommand(
                context.tenantId(),
                AuditActorType.AGENT,
                context.agentId(),
                "TOOL_EXECUTION_SUCCEEDED",
                "TOOL_EXECUTION",
                context.toolExecutionId(),
                context.toolExecutionId(),
                AuditResult.SUCCESS,
                null,
                null,
                null,
                null,
                Map.of(
                        "ticketId", ticket.ticketId(),
                        "ticketNo", ticket.ticketNo(),
                        "resultMessageId",
                        Long.toString(toolMessageId),
                        "toolCallId", context.toolCallId(),
                        "toolName", TOOL_NAME,
                        "status",
                        ToolExecutionStatus.SUCCEEDED
                                .name()
                ),
                null,
                null
        ));

        return new ExecuteCreateTicketToolResult(
                context.toolExecutionId(),
                ticket.ticketId(),
                ticket.ticketNo(),
                ticket.status(),
                toolMessageId,
                toolSequence,
                finalAssistantMessageId,
                finalAssistantSequence,
                newConversationVersion,
                completedAt,
                false
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(
            AgentToolExecutionContext context,
            CreateTicketToolFailure failure
    ) {
        Objects.requireNonNull(
                context,
                "context must not be null"
        );
        Objects.requireNonNull(
                failure,
                "failure must not be null"
        );

        lockConversation(context);

        ToolExecutionRow execution =
                lockExecution(context);

        requireMatchingExecution(execution, context);

        ToolExecutionStatus current = execution.status();

        if (current == ToolExecutionStatus.SUCCEEDED
                || current == ToolExecutionStatus.FAILED
                || current == ToolExecutionStatus.CANCELLED
                || current
                == ToolExecutionStatus.WAITING_APPROVAL) {
            return;
        }

        if (current != ToolExecutionStatus.PENDING
                && current != ToolExecutionStatus.RUNNING) {
            throw new IllegalStateException(
                    "Unexpected tool execution status: "
                            + current
            );
        }

        long durationMs = execution.startedAt() == null
                ? 0L
                : Math.max(
                        0L,
                        Duration.between(
                                execution.startedAt(),
                                failure.failedAt()
                        ).toMillis()
                );

        requireOneRow(
                toolExecutionMapper.markFailed(
                        context.tenantId(),
                        context.toolExecutionId(),
                        context.conversationId(),
                        context.agentId(),
                        context.requestMessageId(),
                        context.toolCallId(),
                        current,
                        failure.errorCode(),
                        failure.safeMessage(),
                        failure.failedAt(),
                        durationMs
                ),
                "Expected tool execution "
                        + "to be marked failed"
        );

        auditLogWriter.write(new AuditLogCommand(
                context.tenantId(),
                AuditActorType.AGENT,
                context.agentId(),
                "TOOL_EXECUTION_FAILED",
                "TOOL_EXECUTION",
                context.toolExecutionId(),
                context.toolExecutionId(),
                AuditResult.FAILURE,
                null,
                null,
                null,
                null,
                Map.of(
                        "toolCallId",
                        context.toolCallId(),
                        "toolName", TOOL_NAME,
                        "status",
                        ToolExecutionStatus.FAILED.name()
                ),
                failure.errorCode(),
                failure.safeMessage()
        ));
    }

    private ClaimedCreateTicketToolExecution claimPending(
            AgentToolExecutionContext context,
            ToolExecutionRow execution,
            Instant startedAt
    ) {
        CreateTicketToolArguments arguments =
                ticketToolJsonCodec.decodeArguments(
                        execution.inputJson()
                );

        requireOneRow(
                toolExecutionMapper.markRunning(
                        context.tenantId(),
                        context.toolExecutionId(),
                        context.conversationId(),
                        context.agentId(),
                        context.requestMessageId(),
                        context.toolCallId(),
                        startedAt
                ),
                "Expected tool execution "
                        + "to be marked running"
        );

        auditLogWriter.write(new AuditLogCommand(
                context.tenantId(),
                AuditActorType.AGENT,
                context.agentId(),
                "TOOL_EXECUTION_STARTED",
                "TOOL_EXECUTION",
                context.toolExecutionId(),
                context.toolExecutionId(),
                AuditResult.SUCCESS,
                null,
                null,
                null,
                null,
                Map.of(
                        "toolCallId",
                        context.toolCallId(),
                        "toolName", TOOL_NAME,
                        "status",
                        ToolExecutionStatus.RUNNING.name(),
                        "startedAt",
                        startedAt.toString()
                ),
                null,
                null
        ));

        return ClaimedCreateTicketToolExecution.fresh(
                context,
                arguments,
                startedAt
        );
    }

    private ClaimedCreateTicketToolExecution replaySucceeded(
            AgentToolExecutionContext context,
            ConversationTurnStateRow conversation,
            ToolExecutionRow execution
    ) {
        CreateTicketToolOutput output =
                ticketToolJsonCodec.decodeOutput(
                        execution.outputJson()
                );

        Long resultMessageId =
                execution.resultMessageId();

        if (resultMessageId == null) {
            throw new IllegalStateException(
                    "Succeeded tool execution is missing "
                            + "its result message"
            );
        }

        ToolCallRequestMessageRow toolMessage =
                Objects.requireNonNull(
                        messageMapper
                                .findOwnedMessageByIdForUpdate(
                                        resultMessageId,
                                        context.tenantId(),
                                        context
                                                .conversationId()
                                ),
                        "messageMapper must not return null"
                ).orElseThrow(() -> new IllegalStateException(
                        "Succeeded tool result message "
                                + "is missing"
                ));

        ToolCallRequestMessageRow assistantMessage =
                Objects.requireNonNull(
                        messageMapper
                                .findOwnedMessageBySequenceForUpdate(
                                        context.tenantId(),
                                        context
                                                .conversationId(),
                                        toolMessage.sequenceNo() + 1
                                ),
                        "messageMapper must not return null"
                ).orElseThrow(() -> new IllegalStateException(
                        "Tool continuation assistant "
                                + "message is missing"
                ));

        requireReplayToolMessage(
                execution,
                toolMessage
        );

        requireReplayAssistantMessage(
                toolMessage,
                assistantMessage
        );

        return ClaimedCreateTicketToolExecution.replay(
                context,
                execution.startedAt(),
                new ExecuteCreateTicketToolResult(
                        execution.id(),
                        output.ticketId(),
                        output.ticketNo(),
                        output.status(),
                        resultMessageId,
                        toolMessage.sequenceNo(),
                        assistantMessage.id(),
                        assistantMessage.sequenceNo(),
                        conversation.version(),
                        assistantMessage.createdAt(),
                        true
                )
        );
    }

    private static void requireReplayToolMessage(
            ToolExecutionRow execution,
            ToolCallRequestMessageRow toolMessage
    ) {
        if (toolMessage.id() != execution.resultMessageId()) {
            throw new IllegalStateException(
                    "Tool result message id must match "
                            + "the succeeded execution"
            );
        }

        if (toolMessage.role() != MessageRole.TOOL) {
            throw new IllegalStateException(
                    "Tool result message must be a "
                            + "TOOL message"
            );
        }

        if (toolMessage.status() != MessageStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Tool result message must be completed"
            );
        }

        if (toolMessage.contentType()
                != MessageContentType.JSON) {
            throw new IllegalStateException(
                    "Tool result message must carry "
                            + "JSON content"
            );
        }

        if (toolMessage.content() == null
                || toolMessage.content().isBlank()) {
            throw new IllegalStateException(
                    "Tool result message content "
                            + "must not be empty"
            );
        }

        if (toolMessage.sequenceNo() <= 0) {
            throw new IllegalStateException(
                    "Tool result message sequence "
                            + "must be positive"
            );
        }
    }

    private static void requireReplayAssistantMessage(
            ToolCallRequestMessageRow toolMessage,
            ToolCallRequestMessageRow assistantMessage
    ) {
        if (assistantMessage.id() <= 0) {
            throw new IllegalStateException(
                    "Continuation assistant message id "
                            + "must be positive"
            );
        }

        if (assistantMessage.id() == toolMessage.id()) {
            throw new IllegalStateException(
                    "Continuation assistant message must "
                            + "not be the tool result message"
            );
        }

        if (assistantMessage.role() != MessageRole.ASSISTANT) {
            throw new IllegalStateException(
                    "Continuation message must be an "
                            + "ASSISTANT message"
            );
        }

        if (assistantMessage.contentType()
                != MessageContentType.TEXT) {
            throw new IllegalStateException(
                    "Continuation message must carry "
                            + "TEXT content"
            );
        }

        if (assistantMessage.sequenceNo()
                != toolMessage.sequenceNo() + 1) {
            throw new IllegalStateException(
                    "Continuation message sequence must "
                            + "immediately follow the tool "
                            + "result message"
            );
        }

        if (assistantMessage.modelName() == null
                || assistantMessage.modelName().isBlank()) {
            throw new IllegalStateException(
                    "Continuation message model name "
                            + "must not be empty"
            );
        }

        if (assistantMessage.status()
                != MessageStatus.CREATING
                && assistantMessage.status()
                != MessageStatus.COMPLETED
                && assistantMessage.status()
                != MessageStatus.FAILED) {
            throw new IllegalStateException(
                    "Continuation message status must be "
                            + "CREATING, COMPLETED or FAILED"
            );
        }
    }

    private ConversationTurnStateRow lockConversation(
            AgentToolExecutionContext context
    ) {
        ConversationTurnStateRow state =
                Objects.requireNonNull(
                        conversationMapper
                                .findOwnedTurnForUpdate(
                                        context.conversationId(),
                                        context.tenantId(),
                                        context
                                                .requesterUserId()
                                ),
                        "conversationMapper must not return null"
                ).orElseThrow(
                        ConversationNotFoundException::new
                );

        if (state.id() != context.conversationId()
                || state.tenantId() != context.tenantId()
                || state.userId()
                != context.requesterUserId()
                || state.agentId() != context.agentId()) {
            throw new IllegalStateException(
                    "Conversation scope mismatch"
            );
        }

        return state;
    }

    private ToolExecutionRow lockExecution(
            AgentToolExecutionContext context
    ) {
        return Objects.requireNonNull(
                toolExecutionMapper
                        .findByTenantIdAndIdForUpdate(
                                context.tenantId(),
                                context.toolExecutionId()
                        ),
                "toolExecutionMapper must not return null"
        ).orElseThrow(
                ToolExecutionNotFoundException::new
        );
    }

    private static void requireActiveConversation(
            ConversationTurnStateRow state,
            AgentToolExecutionContext context
    ) {
        if (state.status() != ConversationStatus.ACTIVE) {
            throw new ConversationNotActiveException(
                    state.status()
            );
        }

        if (state.nextMessageSequence() <= 0) {
            throw new IllegalStateException(
                    "Conversation message sequence "
                            + "is invalid"
            );
        }

        if (state.version() < 0) {
            throw new IllegalStateException(
                    "Conversation version is invalid"
            );
        }
    }

    private static void requireMatchingExecution(
            ToolExecutionRow execution,
            AgentToolExecutionContext context
    ) {
        if (execution.tenantId() != context.tenantId()
                || execution.conversationId()
                != context.conversationId()
                || execution.agentId() != context.agentId()
                || !Objects.equals(
                execution.requestMessageId(),
                context.requestMessageId()
        )
                || !Objects.equals(
                execution.toolCallId(),
                context.toolCallId()
        )
                || !TOOL_NAME.equals(
                execution.toolName()
        )) {
            throw new IllegalStateException(
                    "Tool execution context mismatch"
            );
        }
    }

    private static void requireOneRow(
            int affectedRows,
            String failureMessage
    ) {
        if (affectedRows != 1) {
            throw new IllegalStateException(
                    failureMessage
            );
        }
    }
}
