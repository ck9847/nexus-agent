package com.nexusagent.conversation.internal;

import com.nexusagent.agent.api.ActiveAgentLookup;
import com.nexusagent.agent.api.ActiveAgentReference;
import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.common.id.IdGenerator;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.conversation.api.CreateConversationRequest;
import com.nexusagent.conversation.api.CreateConversationResponse;
import com.nexusagent.conversation.api.CreateConversationService;
import com.nexusagent.conversation.api.CreatedMessageResponse;
import com.nexusagent.conversation.domain.ConversationStatus;
import com.nexusagent.conversation.domain.MessageContentType;
import com.nexusagent.conversation.domain.MessageRole;
import com.nexusagent.conversation.domain.MessageStatus;
import com.nexusagent.conversation.internal.persistence.ConversationMapper;
import com.nexusagent.conversation.internal.persistence.ConversationRow;
import com.nexusagent.conversation.internal.persistence.MessageMapper;
import com.nexusagent.conversation.internal.persistence.MessageRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;

@Service
public class DefaultCreateConversationService
        implements CreateConversationService {

    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MAX_MESSAGE_LENGTH = 50_000;
    private static final long INITIAL_SEQUENCE_NO = 1L;

    private final CurrentActorProvider currentActorProvider;
    private final ActiveAgentLookup activeAgentLookup;
    private final IdGenerator idGenerator;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public DefaultCreateConversationService(
            CurrentActorProvider currentActorProvider,
            ActiveAgentLookup activeAgentLookup,
            IdGenerator idGenerator,
            ConversationMapper conversationMapper,
            MessageMapper messageMapper,
            AuditLogWriter auditLogWriter,
            Clock clock
    ) {
        this.currentActorProvider = currentActorProvider;
        this.activeAgentLookup = activeAgentLookup;
        this.idGenerator = idGenerator;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.auditLogWriter = auditLogWriter;
        this.clock = clock;
    }

    @Override
    @Transactional
    public CreateConversationResponse create(
            CreateConversationRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        CurrentActor actor =
                currentActorProvider.requireCurrentActor();

        String title = normalizeOptional(
                request.title(),
                "title",
                MAX_TITLE_LENGTH
        );

        String initialMessage = normalizeRequired(
                request.initialMessage(),
                "initialMessage",
                MAX_MESSAGE_LENGTH
        );

        ActiveAgentReference agent =
                Objects.requireNonNull(
                        activeAgentLookup.requireActiveAgent(
                                actor.tenantId(),
                                request.agentCode()
                        ),
                        "activeAgentLookup must not return null"
                );

        if (agent.tenantId() != actor.tenantId()) {
            throw new IllegalStateException(
                    "Active Agent lookup returned "
                            + "a cross-tenant reference"
            );
        }

        long conversationId = idGenerator.nextId();
        long messageId = idGenerator.nextId();

        Instant now = clock.instant()
                .truncatedTo(ChronoUnit.MILLIS);

        ConversationRow conversation =
                new ConversationRow(
                        conversationId,
                        actor.tenantId(),
                        actor.userId(),
                        agent.agentId(),
                        title,
                        ConversationStatus.ACTIVE,
                        now,
                        0,
                        now,
                        now
                );

        MessageRow message = new MessageRow(
                messageId,
                actor.tenantId(),
                conversationId,
                INITIAL_SEQUENCE_NO,
                MessageRole.USER,
                initialMessage,
                MessageContentType.TEXT,
                MessageStatus.COMPLETED,
                null,
                null,
                null,
                null,
                now
        );

        int conversationRows =
                conversationMapper.insert(
                        conversation
                );

        if (conversationRows != 1) {
            throw new IllegalStateException(
                    "Expected one conversation row "
                            + "to be inserted"
            );
        }

        int messageRows =
                messageMapper.insert(message);

        if (messageRows != 1) {
            throw new IllegalStateException(
                    "Expected one initial message row "
                            + "to be inserted"
            );
        }

        auditLogWriter.write(new AuditLogCommand(
                actor.tenantId(),
                AuditActorType.USER,
                actor.userId(),
                "CONVERSATION_CREATED",
                "CONVERSATION",
                conversationId,
                null,
                AuditResult.SUCCESS,
                null,
                null,
                null,
                null,
                Map.of(
                        "agentId",
                        Long.toString(agent.agentId()),
                        "agentCode",
                        agent.code(),
                        "status",
                        ConversationStatus.ACTIVE.name(),
                        "version",
                        0,
                        "initialMessageId",
                        Long.toString(messageId),
                        "initialMessageSequenceNo",
                        INITIAL_SEQUENCE_NO,
                        "initialMessageRole",
                        MessageRole.USER.name(),
                        "initialMessageContentType",
                        MessageContentType.TEXT.name(),
                        "initialMessageStatus",
                        MessageStatus.COMPLETED.name()
                ),
                null,
                null
        ));

        CreatedMessageResponse initialMessageResponse =
                new CreatedMessageResponse(
                        Long.toString(messageId),
                        INITIAL_SEQUENCE_NO,
                        MessageRole.USER,
                        initialMessage,
                        MessageContentType.TEXT,
                        MessageStatus.COMPLETED,
                        now
                );

        return new CreateConversationResponse(
                Long.toString(conversationId),
                Long.toString(agent.agentId()),
                agent.code(),
                title,
                ConversationStatus.ACTIVE,
                0,
                now,
                now,
                now,
                initialMessageResponse
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
}