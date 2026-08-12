package com.nexusagent.conversation.internal;

import com.nexusagent.agent.api.ActiveAgentRuntime;
import com.nexusagent.agent.api.AgentRuntimeLookup;
import com.nexusagent.agent.domain.AgentModelConfig;
import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.common.id.IdGenerator;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.conversation.api.ConversationNotActiveException;
import com.nexusagent.conversation.api.ConversationNotFoundException;
import com.nexusagent.conversation.api.ConversationTurnInProgressException;
import com.nexusagent.conversation.domain.ConversationStatus;
import com.nexusagent.conversation.domain.MessageContentType;
import com.nexusagent.conversation.domain.MessageRole;
import com.nexusagent.conversation.domain.MessageStatus;
import com.nexusagent.conversation.internal.persistence.ConversationMapper;
import com.nexusagent.conversation.internal.persistence.ConversationTurnMessageRow;
import com.nexusagent.conversation.internal.persistence.ConversationTurnStateRow;
import com.nexusagent.conversation.internal.persistence.MessageMapper;
import com.nexusagent.conversation.internal.persistence.MessageRow;
import com.nexusagent.model.api.ChatModelMessage;
import com.nexusagent.model.api.ChatModelOptions;
import com.nexusagent.model.api.ChatModelRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class DefaultPrepareConversationTurnService
        implements PrepareConversationTurnService {

    private static final int HISTORY_LIMIT = 49;

    private final CurrentActorProvider currentActorProvider;
    private final AgentRuntimeLookup agentRuntimeLookup;
    private final IdGenerator idGenerator;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public DefaultPrepareConversationTurnService(
            CurrentActorProvider currentActorProvider,
            AgentRuntimeLookup agentRuntimeLookup,
            IdGenerator idGenerator,
            ConversationMapper conversationMapper,
            MessageMapper messageMapper,
            AuditLogWriter auditLogWriter,
            Clock clock
    ) {
        this.currentActorProvider = Objects.requireNonNull(
                currentActorProvider
        );
        this.agentRuntimeLookup = Objects.requireNonNull(
                agentRuntimeLookup
        );
        this.idGenerator = Objects.requireNonNull(idGenerator);
        this.conversationMapper = Objects.requireNonNull(
                conversationMapper
        );
        this.messageMapper = Objects.requireNonNull(
                messageMapper
        );
        this.auditLogWriter = Objects.requireNonNull(
                auditLogWriter
        );
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PreparedConversationTurn prepare(
            String conversationId,
            String rawContent
    ) {
        CurrentActor actor =
                currentActorProvider.requireCurrentActor();

        long parsedConversationId =
                ConversationIdParser.parse(conversationId);

        String content =
                ConversationMessageContentNormalizer.normalize(
                        rawContent
                );

        ConversationTurnStateRow state =
                Objects.requireNonNull(
                        conversationMapper
                                .findOwnedTurnForUpdate(
                                        parsedConversationId,
                                        actor.tenantId(),
                                        actor.userId()
                                ),
                        "conversationMapper must not return null"
                ).orElseThrow(
                        ConversationNotFoundException::new
                );

        validateState(
                state,
                parsedConversationId,
                actor
        );

        if (state.status() != ConversationStatus.ACTIVE) {
            throw new ConversationNotActiveException(
                    state.status()
            );
        }

        boolean turnInProgress =
                messageMapper.existsCreatingAssistantForOwner(
                        parsedConversationId,
                        actor.tenantId(),
                        actor.userId()
                );

        if (turnInProgress) {
            throw new ConversationTurnInProgressException();
        }

        ActiveAgentRuntime agent =
                agentRuntimeLookup.requireActiveAgent(
                        actor.tenantId(),
                        state.agentId()
                );

        validateAgent(agent, state, actor);

        List<ConversationTurnMessageRow> history =
                Objects.requireNonNull(
                        messageMapper
                                .findRecentCompletedTurnMessages(
                                        parsedConversationId,
                                        actor.tenantId(),
                                        actor.userId(),
                                        HISTORY_LIMIT
                                ),
                        "messageMapper must not return null"
                );

        List<ChatModelMessage> modelMessages =
                mapHistory(history);

        modelMessages.add(
                ChatModelMessage.user(content)
        );

        long userMessageId = idGenerator.nextId();
        long assistantMessageId = idGenerator.nextId();

        validateGeneratedIds(
                userMessageId,
                assistantMessageId
        );

        Instant now = clock.instant()
                .truncatedTo(ChronoUnit.MILLIS);

        long userSequenceNo =
                state.nextMessageSequence();

        long assistantSequenceNo =
                userSequenceNo + 1;

        MessageRow userMessage = new MessageRow(
                userMessageId,
                actor.tenantId(),
                parsedConversationId,
                userSequenceNo,
                MessageRole.USER,
                content,
                MessageContentType.TEXT,
                MessageStatus.COMPLETED,
                null,
                null,
                null,
                null,
                now
        );

        MessageRow assistantPlaceholder =
                new MessageRow(
                        assistantMessageId,
                        actor.tenantId(),
                        parsedConversationId,
                        assistantSequenceNo,
                        MessageRole.ASSISTANT,
                        "",
                        MessageContentType.TEXT,
                        MessageStatus.CREATING,
                        agent.modelName(),
                        null,
                        null,
                        null,
                        now
                );

        requireOneRow(
                messageMapper.insert(userMessage),
                "user message"
        );

        requireOneRow(
                messageMapper.insert(assistantPlaceholder),
                "assistant placeholder"
        );

        requireOneRow(
                conversationMapper.advanceForPreparedTurn(
                        parsedConversationId,
                        actor.tenantId(),
                        actor.userId(),
                        userSequenceNo,
                        state.version(),
                        now
                ),
                "conversation"
        );

        int newVersion = state.version() + 1;

        auditLogWriter.write(new AuditLogCommand(
                actor.tenantId(),
                AuditActorType.USER,
                actor.userId(),
                "CONVERSATION_TURN_PREPARED",
                "CONVERSATION",
                parsedConversationId,
                null,
                AuditResult.SUCCESS,
                null,
                null,
                null,
                null,
                Map.of(
                        "agentId",
                        Long.toString(agent.agentId()),
                        "userMessageId",
                        Long.toString(userMessageId),
                        "userSequenceNo",
                        userSequenceNo,
                        "userStatus",
                        MessageStatus.COMPLETED.name(),
                        "assistantMessageId",
                        Long.toString(assistantMessageId),
                        "assistantSequenceNo",
                        assistantSequenceNo,
                        "assistantStatus",
                        MessageStatus.CREATING.name(),
                        "conversationVersion",
                        newVersion
                ),
                null,
                null
        ));

        ChatModelRequest modelRequest =
                new ChatModelRequest(
                        agent.modelName(),
                        agent.systemPrompt(),
                        modelOptions(agent.modelConfig()),
                        modelMessages,
                        List.of()
                );

        return new PreparedConversationTurn(
                actor.tenantId(),
                actor.userId(),
                parsedConversationId,
                agent,
                userMessageId,
                userSequenceNo,
                assistantMessageId,
                assistantSequenceNo,
                newVersion,
                now,
                modelRequest
        );
    }

    private static List<ChatModelMessage> mapHistory(
            List<ConversationTurnMessageRow> rows
    ) {
        if (rows.size() > HISTORY_LIMIT) {
            throw new IllegalStateException(
                    "Message mapper returned too many rows"
            );
        }

        long previousSequence = Long.MAX_VALUE;

        for (ConversationTurnMessageRow row : rows) {
            if (row == null
                    || row.sequenceNo() <= 0
                    || row.sequenceNo() >= previousSequence
                    || row.role() == null
                    || row.status()
                    != MessageStatus.COMPLETED
                    || row.content() == null
                    || row.content().isBlank()) {
                throw new IllegalStateException(
                        "Message mapper returned "
                                + "invalid turn history"
                );
            }

            if (row.role() != MessageRole.USER
                    && row.role() != MessageRole.ASSISTANT) {
                throw new IllegalStateException(
                        "Message mapper returned "
                                + "an unsupported role"
                );
            }

            previousSequence = row.sequenceNo();
        }

        List<ConversationTurnMessageRow> chronological =
                new ArrayList<>(rows);

        Collections.reverse(chronological);

        List<ChatModelMessage> result =
                new ArrayList<>(
                        chronological.size() + 1
                );

        for (ConversationTurnMessageRow row
                : chronological) {
            result.add(
                    row.role() == MessageRole.USER
                            ? ChatModelMessage.user(
                            row.content()
                    )
                            : ChatModelMessage.assistant(
                            row.content()
                    )
            );
        }

        return result;
    }

    private static ChatModelOptions modelOptions(
            AgentModelConfig config
    ) {
        if (config == null) {
            return ChatModelOptions.defaults();
        }

        return new ChatModelOptions(
                config.temperature(),
                config.topP(),
                config.maxOutputTokens()
        );
    }

    private static void validateState(
            ConversationTurnStateRow state,
            long conversationId,
            CurrentActor actor
    ) {
        if (state.id() != conversationId
                || state.tenantId() != actor.tenantId()
                || state.userId() != actor.userId()) {
            throw new IllegalStateException(
                    "Conversation mapper returned "
                            + "a row outside ownership scope"
            );
        }

        if (state.agentId() <= 0
                || state.status() == null
                || state.nextMessageSequence() <= 0
                || state.nextMessageSequence()
                > Long.MAX_VALUE - 2
                || state.version() < 0
                || state.version()
                == Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "Conversation mapper returned "
                            + "an invalid turn state"
            );
        }
    }

    private static void validateAgent(
            ActiveAgentRuntime agent,
            ConversationTurnStateRow state,
            CurrentActor actor
    ) {
        if (agent.agentId() != state.agentId()
                || agent.tenantId() != actor.tenantId()) {
            throw new IllegalStateException(
                    "Agent runtime does not match conversation"
            );
        }
    }

    private static void validateGeneratedIds(
            long userMessageId,
            long assistantMessageId
    ) {
        if (userMessageId <= 0
                || assistantMessageId <= 0
                || userMessageId == assistantMessageId) {
            throw new IllegalStateException(
                    "IdGenerator returned invalid message IDs"
            );
        }
    }

    private static void requireOneRow(
            int rows,
            String resource
    ) {
        if (rows != 1) {
            throw new IllegalStateException(
                    "Expected one " + resource
                            + " row to be affected"
            );
        }
    }
}