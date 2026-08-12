package com.nexusagent.conversation.internal;

import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.conversation.api.ConversationDetailResponse;
import com.nexusagent.conversation.api.ConversationMessageResponse;
import com.nexusagent.conversation.api.ConversationMessagesQuery;
import com.nexusagent.conversation.api.ConversationMessagesResponse;
import com.nexusagent.conversation.api.ConversationNotFoundException;
import com.nexusagent.conversation.api.ConversationQueryService;
import com.nexusagent.conversation.api.InvalidConversationQueryException;
import com.nexusagent.conversation.domain.MessageRole;
import com.nexusagent.conversation.internal.persistence.ConversationDetailRow;
import com.nexusagent.conversation.internal.persistence.ConversationMapper;
import com.nexusagent.conversation.internal.persistence.ConversationMessageListRow;
import com.nexusagent.conversation.internal.persistence.MessageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class DefaultConversationQueryService
        implements ConversationQueryService {

    private final CurrentActorProvider currentActorProvider;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ConversationMessageCursorCodec
            cursorCodec;

    public DefaultConversationQueryService(
            CurrentActorProvider currentActorProvider,
            ConversationMapper conversationMapper,
            MessageMapper messageMapper,
            ConversationMessageCursorCodec cursorCodec
    ) {
        this.currentActorProvider = currentActorProvider;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.cursorCodec = cursorCodec;
    }

    @Override
    @Transactional(readOnly = true)
    public ConversationDetailResponse getById(
            String conversationId
    ) {
        CurrentActor actor =
                currentActorProvider.requireCurrentActor();

        long parsedConversationId =
                ConversationIdParser.parse(
                        conversationId
                );

        ConversationDetailRow row =
                requireOwnedDetail(
                        parsedConversationId,
                        actor
                );

        return toDetailResponse(row);
    }

    @Override
    @Transactional(readOnly = true)
    public ConversationMessagesResponse listMessages(
            String conversationId,
            ConversationMessagesQuery query
    ) {
        Objects.requireNonNull(
                query,
                "query must not be null"
        );

        CurrentActor actor =
                currentActorProvider.requireCurrentActor();

        long parsedConversationId =
                ConversationIdParser.parse(
                        conversationId
                );

        /*
         * 必须先验证会话所有权，再解析 cursor。
         * 否则访问别人的会话时，恶意 cursor
         * 可能先返回 400，从而泄露资源是否存在。
         */
        requireOwnedDetail(
                parsedConversationId,
                actor
        );

        ConversationMessageCursor pageCursor =
                decodeCursor(
                        query.cursor(),
                        parsedConversationId
                );

        Long beforeSequenceNo =
                pageCursor == null
                        ? null
                        : pageCursor.sequenceNo();

        int fetchLimit = query.limit() + 1;

        List<ConversationMessageListRow> rows =
                Objects.requireNonNull(
                        messageMapper
                                .findOwnedMessagePage(
                                        parsedConversationId,
                                        actor.tenantId(),
                                        actor.userId(),
                                        beforeSequenceNo,
                                        fetchLimit
                                ),
                        "messageMapper must not "
                                + "return null"
                );

        validateMessageRows(
                rows,
                parsedConversationId,
                actor.tenantId(),
                fetchLimit
        );

        boolean hasMore =
                rows.size() > query.limit();

        int returnedCount =
                Math.min(
                        rows.size(),
                        query.limit()
                );

        List<ConversationMessageListRow>
                pageRows =
                new ArrayList<>(
                        rows.subList(
                                0,
                                returnedCount
                        )
                );

        String nextCursor = null;

        if (hasMore) {
            ConversationMessageListRow
                    oldestReturnedRow =
                    pageRows.get(
                            pageRows.size() - 1
                    );

            nextCursor = cursorCodec.encode(
                    new ConversationMessageCursor(
                            parsedConversationId,
                            oldestReturnedRow.sequenceNo()
                    )
            );

            if (nextCursor == null
                    || nextCursor.isBlank()) {
                throw new IllegalStateException(
                        "Cursor codec returned "
                                + "an invalid cursor"
                );
            }
        }

        /*
         * SQL 返回 DESC：
         * 7, 6, 5
         *
         * API 返回 ASC：
         * 5, 6, 7
         */
        Collections.reverse(pageRows);

        List<ConversationMessageResponse> items =
                pageRows.stream()
                        .map(
                                DefaultConversationQueryService
                                        ::toMessageResponse
                        )
                        .toList();

        return new ConversationMessagesResponse(
                items,
                nextCursor,
                hasMore
        );
    }

    private ConversationDetailRow requireOwnedDetail(
            long conversationId,
            CurrentActor actor
    ) {
        ConversationDetailRow row =
                Objects.requireNonNull(
                        conversationMapper
                                .findOwnedDetail(
                                        conversationId,
                                        actor.tenantId(),
                                        actor.userId()
                                ),
                        "conversationMapper must not "
                                + "return null"
                ).orElseThrow(
                        ConversationNotFoundException::new
                );

        validateDetailRow(
                row,
                conversationId,
                actor
        );

        return row;
    }

    private ConversationMessageCursor decodeCursor(
            String cursor,
            long conversationId
    ) {
        if (cursor == null) {
            return null;
        }

        ConversationMessageCursor decoded =
                Objects.requireNonNull(
                        cursorCodec.decode(cursor),
                        "cursorCodec must not "
                                + "return null"
                );

        if (decoded.conversationId()
                != conversationId) {
            throw invalidCursor();
        }

        return decoded;
    }

    private static void validateDetailRow(
            ConversationDetailRow row,
            long expectedConversationId,
            CurrentActor actor
    ) {
        if (row.id() != expectedConversationId
                || row.tenantId()
                != actor.tenantId()
                || row.userId()
                != actor.userId()) {
            throw new IllegalStateException(
                    "Conversation detail query "
                            + "returned a row outside "
                            + "the requested scope"
            );
        }

        if (row.id() <= 0
                || row.agentId() <= 0
                || row.status() == null
                || row.lastMessageAt() == null
                || row.version() < 0
                || row.createdAt() == null
                || row.updatedAt() == null
                || row.updatedAt().isBefore(
                row.createdAt()
        )
                || row.lastMessageAt().isBefore(
                row.createdAt()
        )
                || row.updatedAt().isBefore(
                row.lastMessageAt()
        )) {
            throw new IllegalStateException(
                    "Conversation detail query "
                            + "returned an invalid row"
            );
        }
    }

    private static void validateMessageRows(
            List<ConversationMessageListRow> rows,
            long expectedConversationId,
            long expectedTenantId,
            int fetchLimit
    ) {
        if (rows.size() > fetchLimit) {
            throw new IllegalStateException(
                    "Message query returned more "
                            + "rows than requested"
            );
        }

        Long previousSequenceNo = null;
        Set<Long> messageIds = new HashSet<>();

        for (ConversationMessageListRow row : rows) {
            if (row.id() <= 0
                    || row.tenantId()
                    != expectedTenantId
                    || row.conversationId()
                    != expectedConversationId
                    || row.sequenceNo() <= 0
                    || !messageIds.add(row.id())
                    || !isPublicRole(row.role())
                    || row.content() == null
                    || row.contentType() == null
                    || row.status() == null
                    || row.createdAt() == null) {
                throw new IllegalStateException(
                        "Message query returned "
                                + "an invalid row"
                );
            }

            if (previousSequenceNo != null
                    && row.sequenceNo()
                    >= previousSequenceNo) {
                throw new IllegalStateException(
                        "Message query returned rows "
                                + "outside strict descending "
                                + "sequence order"
                );
            }

            previousSequenceNo =
                    row.sequenceNo();
        }
    }

    private static boolean isPublicRole(
            MessageRole role
    ) {
        return role == MessageRole.USER
                || role == MessageRole.ASSISTANT;
    }

    private static ConversationDetailResponse
    toDetailResponse(
            ConversationDetailRow row
    ) {
        return new ConversationDetailResponse(
                Long.toString(row.id()),
                Long.toString(row.agentId()),
                row.title(),
                row.status(),
                row.lastMessageAt(),
                row.version(),
                row.createdAt(),
                row.updatedAt()
        );
    }

    private static ConversationMessageResponse
    toMessageResponse(
            ConversationMessageListRow row
    ) {
        return new ConversationMessageResponse(
                Long.toString(row.id()),
                row.sequenceNo(),
                row.role(),
                row.content(),
                row.contentType(),
                row.status(),
                row.createdAt()
        );
    }

    private static InvalidConversationQueryException
    invalidCursor() {
        return new InvalidConversationQueryException(
                "Invalid conversation message cursor"
        );
    }
}