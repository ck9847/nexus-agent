package com.nexusagent.conversation.internal;

import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.common.id.IdGenerator;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.conversation.api.AppendUserMessageRequest;
import com.nexusagent.conversation.api.AppendUserMessageResponse;
import com.nexusagent.conversation.api.AppendUserMessageService;
import com.nexusagent.conversation.api.ConversationNotActiveException;
import com.nexusagent.conversation.api.ConversationNotFoundException;
import com.nexusagent.conversation.api.CreatedMessageResponse;
import com.nexusagent.conversation.domain.ConversationStatus;
import com.nexusagent.conversation.domain.MessageContentType;
import com.nexusagent.conversation.domain.MessageRole;
import com.nexusagent.conversation.domain.MessageStatus;
import com.nexusagent.conversation.internal.persistence.ConversationAppendStateRow;
import com.nexusagent.conversation.internal.persistence.ConversationMapper;
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
public class DefaultAppendUserMessageService
        implements AppendUserMessageService {

    private final CurrentActorProvider currentActorProvider;
    private final IdGenerator idGenerator;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public DefaultAppendUserMessageService(
            CurrentActorProvider currentActorProvider,
            IdGenerator idGenerator,
            ConversationMapper conversationMapper,
            MessageMapper messageMapper,
            AuditLogWriter auditLogWriter,
            Clock clock
    ) {
        this.currentActorProvider = currentActorProvider;
        this.idGenerator = idGenerator;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.auditLogWriter = auditLogWriter;
        this.clock = clock;
    }

    @Override
    @Transactional
    public AppendUserMessageResponse append(
            String conversationId,
            AppendUserMessageRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request must not be null"
        );

        CurrentActor actor =
                currentActorProvider.requireCurrentActor();

        long parsedConversationId =
                ConversationIdParser.parse(
                        conversationId
                );

        String content =
                ConversationMessageContentNormalizer.normalize(
                        request.content()
                );

        ConversationAppendStateRow state =
                Objects.requireNonNull(
                        conversationMapper.findOwnedForUpdate(
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

        long messageId = idGenerator.nextId();

        Instant now = clock.instant()
                .truncatedTo(ChronoUnit.MILLIS);

        long sequenceNo = state.nextMessageSequence();
        int newVersion = state.version() + 1;

        MessageRow message = new MessageRow(
                messageId,
                actor.tenantId(),
                parsedConversationId,
                sequenceNo,
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

        int messageRows = messageMapper.insert(message);

        if (messageRows != 1) {
            throw new IllegalStateException(
                    "Expected one message row to be inserted"
            );
        }

        int conversationRows =
                conversationMapper.advanceMessageSequence(
                        parsedConversationId,
                        actor.tenantId(),
                        actor.userId(),
                        sequenceNo,
                        state.version(),
                        now
                );

        if (conversationRows != 1) {
            throw new IllegalStateException(
                    "Expected one conversation row "
                            + "to be advanced"
            );
        }

        auditLogWriter.write(new AuditLogCommand(
                actor.tenantId(),
                AuditActorType.USER,
                actor.userId(),
                "CONVERSATION_MESSAGE_APPENDED",
                "MESSAGE",
                messageId,
                null,
                AuditResult.SUCCESS,
                null,
                null,
                null,
                null,
                Map.of(
                        "conversationId",
                        Long.toString(parsedConversationId),
                        "sequenceNo",
                        sequenceNo,
                        "role",
                        MessageRole.USER.name(),
                        "contentType",
                        MessageContentType.TEXT.name(),
                        "status",
                        MessageStatus.COMPLETED.name(),
                        "conversationVersion",
                        newVersion
                ),
                null,
                null
        ));

        CreatedMessageResponse createdMessage =
                new CreatedMessageResponse(
                        Long.toString(messageId),
                        sequenceNo,
                        MessageRole.USER,
                        content,
                        MessageContentType.TEXT,
                        MessageStatus.COMPLETED,
                        now
                );

        return new AppendUserMessageResponse(
                Long.toString(parsedConversationId),
                newVersion,
                now,
                createdMessage
        );
    }

    private static void validateState(
            ConversationAppendStateRow state,
            long expectedConversationId,
            CurrentActor actor
    ) {
        if (state.id() != expectedConversationId
                || state.tenantId() != actor.tenantId()
                || state.userId() != actor.userId()) {
            throw new IllegalStateException(
                    "Conversation mapper returned "
                            + "a row outside the requested "
                            + "ownership scope"
            );
        }

        if (state.status() == null
                || state.nextMessageSequence() <= 0
                || state.nextMessageSequence()
                == Long.MAX_VALUE
                || state.version() < 0
                || state.version()
                == Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "Conversation mapper returned "
                            + "an invalid append state"
            );
        }
    }
}