package com.nexusagent.conversation.internal;

import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.conversation.api.ConversationDetailResponse;
import com.nexusagent.conversation.api.ConversationMessageResponse;
import com.nexusagent.conversation.api.ConversationMessagesQuery;
import com.nexusagent.conversation.api.ConversationMessagesResponse;
import com.nexusagent.conversation.api.ConversationNotFoundException;
import com.nexusagent.conversation.api.InvalidConversationQueryException;
import com.nexusagent.conversation.domain.ConversationStatus;
import com.nexusagent.conversation.domain.MessageContentType;
import com.nexusagent.conversation.domain.MessageRole;
import com.nexusagent.conversation.domain.MessageStatus;
import com.nexusagent.conversation.internal.persistence.ConversationDetailRow;
import com.nexusagent.conversation.internal.persistence.ConversationMapper;
import com.nexusagent.conversation.internal.persistence.ConversationMessageListRow;
import com.nexusagent.conversation.internal.persistence.MessageMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultConversationQueryServiceTest {

    private static final long USER_ID = 101L;
    private static final long TENANT_ID = 202L;
    private static final long AGENT_ID = 301L;
    private static final long CONVERSATION_ID = 901L;

    private static final CurrentActor ACTOR =
            new CurrentActor(
                    USER_ID,
                    TENANT_ID,
                    "member",
                    Set.of("MEMBER")
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-12T00:00:00Z"
            );

    private static final Instant UPDATED_AT =
            Instant.parse(
                    "2026-08-12T00:05:00Z"
            );

    private static final ConversationDetailRow DETAIL =
            new ConversationDetailRow(
                    CONVERSATION_ID,
                    TENANT_ID,
                    USER_ID,
                    AGENT_ID,
                    "Production issue",
                    ConversationStatus.ACTIVE,
                    UPDATED_AT,
                    6,
                    CREATED_AT,
                    UPDATED_AT
            );

    @Mock
    private CurrentActorProvider currentActorProvider;

    @Mock
    private ConversationMapper conversationMapper;

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private ConversationMessageCursorCodec cursorCodec;

    @InjectMocks
    private DefaultConversationQueryService service;

    @Test
    void shouldReturnOwnedConversationDetail() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.of(DETAIL));

        ConversationDetailResponse response =
                service.getById(
                        Long.toString(CONVERSATION_ID)
                );

        assertEquals(
                new ConversationDetailResponse(
                        "901",
                        "301",
                        "Production issue",
                        ConversationStatus.ACTIVE,
                        UPDATED_AT,
                        6,
                        CREATED_AT,
                        UPDATED_AT
                ),
                response
        );
    }

    @Test
    void shouldReturnNotFoundForMissingConversation() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.empty());

        assertThrows(
                ConversationNotFoundException.class,
                () -> service.getById(
                        Long.toString(CONVERSATION_ID)
                )
        );
    }

    @Test
    void shouldRejectNullOptionalFromConversationMapper() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(null);

        assertThrows(
                NullPointerException.class,
                () -> service.getById(
                        Long.toString(CONVERSATION_ID)
                )
        );
    }

    @Test
    void shouldRejectConversationRowOutsideScope() {
        ConversationDetailRow outsideScope =
                new ConversationDetailRow(
                        CONVERSATION_ID,
                        TENANT_ID + 1,
                        USER_ID,
                        AGENT_ID,
                        "Production issue",
                        ConversationStatus.ACTIVE,
                        UPDATED_AT,
                        6,
                        CREATED_AT,
                        UPDATED_AT
                );

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.of(outsideScope));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.getById(
                        Long.toString(CONVERSATION_ID)
                )
        );

        assertEquals(
                "Conversation detail query returned "
                        + "a row outside the requested scope",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectInvalidConversationDetailRow() {
        ConversationDetailRow invalid =
                new ConversationDetailRow(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        AGENT_ID,
                        "Production issue",
                        ConversationStatus.ACTIVE,
                        UPDATED_AT,
                        -1,
                        CREATED_AT,
                        UPDATED_AT
                );

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.of(invalid));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.getById(
                        Long.toString(CONVERSATION_ID)
                )
        );

        assertEquals(
                "Conversation detail query "
                        + "returned an invalid row",
                exception.getMessage()
        );
    }

    @ParameterizedTest
    @EnumSource(
            value = ConversationStatus.class,
            names = {"ACTIVE", "COMPLETED", "ARCHIVED"}
    )
    void shouldAllowActiveCompletedAndArchivedDetails(
            ConversationStatus status
    ) {
        ConversationDetailRow row =
                new ConversationDetailRow(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        AGENT_ID,
                        "Production issue",
                        status,
                        UPDATED_AT,
                        6,
                        CREATED_AT,
                        UPDATED_AT
                );

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.of(row));

        ConversationDetailResponse response =
                service.getById(
                        Long.toString(CONVERSATION_ID)
                );

        assertEquals(status, response.status());
    }

    @Test
    void shouldStopWhenCurrentActorCannotBeResolved() {
        when(currentActorProvider.requireCurrentActor())
                .thenThrow(
                        new IllegalStateException(
                                "no authenticated actor"
                        )
                );

        assertThrows(
                IllegalStateException.class,
                () -> service.getById(
                        Long.toString(CONVERSATION_ID)
                )
        );

        verifyNoInteractions(
                conversationMapper,
                messageMapper
        );
    }

    @Test
    void shouldRejectInvalidConversationIdBeforeQueryingMapper() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.getById("abc")
        );

        assertEquals(
                "conversationId must be "
                        + "a positive integer",
                exception.getMessage()
        );

        verifyNoInteractions(
                conversationMapper,
                messageMapper
        );
    }

    @Test
    void shouldReturnFirstPageInAscendingOrder() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.of(DETAIL));
        when(messageMapper.findOwnedMessagePage(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                null,
                4
        )).thenReturn(List.of(
                userMessage(7L),
                userMessage(6L),
                userMessage(5L),
                userMessage(4L)
        ));
        when(cursorCodec.encode(
                new ConversationMessageCursor(
                        CONVERSATION_ID,
                        5L
                )
        )).thenReturn("cursor-for-5");

        ConversationMessagesResponse response =
                service.listMessages(
                        Long.toString(CONVERSATION_ID),
                        new ConversationMessagesQuery(
                                3,
                                null
                        )
                );

        assertEquals(
                List.of(5L, 6L, 7L),
                response.items().stream()
                        .map(
                                ConversationMessageResponse
                                        ::sequenceNo
                        )
                        .toList()
        );
        assertEquals(true, response.hasMore());
        assertEquals(
                "cursor-for-5",
                response.nextCursor()
        );

        verify(messageMapper).findOwnedMessagePage(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                null,
                4
        );
        verify(cursorCodec).encode(
                new ConversationMessageCursor(
                        CONVERSATION_ID,
                        5L
                )
        );
    }

    @Test
    void shouldDecodeCursorAndLoadOlderMessages() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.of(DETAIL));
        when(cursorCodec.decode("cursor-for-5"))
                .thenReturn(
                        new ConversationMessageCursor(
                                CONVERSATION_ID,
                                5L
                        )
                );
        when(messageMapper.findOwnedMessagePage(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                5L,
                4
        )).thenReturn(List.of(
                userMessage(4L),
                userMessage(3L),
                userMessage(2L)
        ));

        ConversationMessagesResponse response =
                service.listMessages(
                        Long.toString(CONVERSATION_ID),
                        new ConversationMessagesQuery(
                                3,
                                "cursor-for-5"
                        )
                );

        verify(messageMapper).findOwnedMessagePage(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                5L,
                4
        );

        assertEquals(
                List.of(2L, 3L, 4L),
                response.items().stream()
                        .map(
                                ConversationMessageResponse
                                        ::sequenceNo
                        )
                        .toList()
        );
        assertEquals(false, response.hasMore());
        assertEquals(null, response.nextCursor());
    }

    @Test
    void shouldReturnLastPageWithoutCursor() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.of(DETAIL));
        when(messageMapper.findOwnedMessagePage(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                null,
                4
        )).thenReturn(List.of(
                userMessage(2L),
                userMessage(1L)
        ));

        ConversationMessagesResponse response =
                service.listMessages(
                        Long.toString(CONVERSATION_ID),
                        new ConversationMessagesQuery(
                                3,
                                null
                        )
                );

        assertEquals(
                List.of(1L, 2L),
                response.items().stream()
                        .map(
                                ConversationMessageResponse
                                        ::sequenceNo
                        )
                        .toList()
        );
        assertEquals(false, response.hasMore());
        assertEquals(null, response.nextCursor());

        verifyNoInteractions(cursorCodec);
    }

    @Test
    void shouldReturnEmptyPage() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.of(DETAIL));
        when(messageMapper.findOwnedMessagePage(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                null,
                4
        )).thenReturn(List.of());

        ConversationMessagesResponse response =
                service.listMessages(
                        Long.toString(CONVERSATION_ID),
                        new ConversationMessagesQuery(
                                3,
                                null
                        )
                );

        assertEquals(
                new ConversationMessagesResponse(
                        List.of(),
                        null,
                        false
                ),
                response
        );
    }

    @Test
    void shouldRejectCursorBelongingToAnotherConversation() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.of(DETAIL));
        when(cursorCodec.decode("cursor-for-other"))
                .thenReturn(
                        new ConversationMessageCursor(
                                CONVERSATION_ID + 1,
                                5L
                        )
                );

        InvalidConversationQueryException exception =
                assertThrows(
                        InvalidConversationQueryException
                                .class,
                        () -> service.listMessages(
                                Long.toString(
                                        CONVERSATION_ID
                                ),
                                new ConversationMessagesQuery(
                                        3,
                                        "cursor-for-other"
                                )
                        )
                );

        assertEquals(
                "Invalid conversation message cursor",
                exception.getMessage()
        );

        verifyNoInteractions(messageMapper);
    }

    @Test
    void shouldPropagateMalformedCursor() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.of(DETAIL));
        when(cursorCodec.decode("malformed"))
                .thenThrow(
                        new InvalidConversationQueryException(
                                "Invalid conversation "
                                        + "message cursor"
                        )
                );

        InvalidConversationQueryException exception =
                assertThrows(
                        InvalidConversationQueryException
                                .class,
                        () -> service.listMessages(
                                Long.toString(
                                        CONVERSATION_ID
                                ),
                                new ConversationMessagesQuery(
                                        3,
                                        "malformed"
                                )
                        )
                );

        assertEquals(
                "Invalid conversation message cursor",
                exception.getMessage()
        );

        verifyNoInteractions(messageMapper);
    }

    @Test
    void shouldRejectNullCursorReturnedByCodec() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.of(DETAIL));
        when(cursorCodec.decode("cursor"))
                .thenReturn(null);

        assertThrows(
                NullPointerException.class,
                () -> service.listMessages(
                        Long.toString(CONVERSATION_ID),
                        new ConversationMessagesQuery(
                                3,
                                "cursor"
                        )
                )
        );

        verifyNoInteractions(messageMapper);
    }

    @Test
    void shouldRejectNullMessageList() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.of(DETAIL));
        when(messageMapper.findOwnedMessagePage(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                null,
                4
        )).thenReturn(null);

        assertThrows(
                NullPointerException.class,
                () -> service.listMessages(
                        Long.toString(CONVERSATION_ID),
                        new ConversationMessagesQuery(
                                3,
                                null
                        )
                )
        );
    }

    @Test
    void shouldRejectMessageRowsOutsideScope() {
        ConversationMessageListRow wrongTenant =
                new ConversationMessageListRow(
                        9001L,
                        TENANT_ID + 1,
                        CONVERSATION_ID,
                        7L,
                        MessageRole.USER,
                        "content",
                        MessageContentType.TEXT,
                        MessageStatus.COMPLETED,
                        UPDATED_AT
                );

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.of(DETAIL));
        when(messageMapper.findOwnedMessagePage(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                null,
                4
        )).thenReturn(List.of(wrongTenant));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.listMessages(
                        Long.toString(CONVERSATION_ID),
                        new ConversationMessagesQuery(
                                3,
                                null
                        )
                )
        );

        assertEquals(
                "Message query returned "
                        + "an invalid row",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectInternalRoles() {
        ConversationMessageListRow toolMessage =
                new ConversationMessageListRow(
                        9001L,
                        TENANT_ID,
                        CONVERSATION_ID,
                        7L,
                        MessageRole.TOOL,
                        "tool call",
                        MessageContentType.JSON,
                        MessageStatus.COMPLETED,
                        UPDATED_AT
                );

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.of(DETAIL));
        when(messageMapper.findOwnedMessagePage(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                null,
                4
        )).thenReturn(List.of(toolMessage));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.listMessages(
                        Long.toString(CONVERSATION_ID),
                        new ConversationMessagesQuery(
                                3,
                                null
                        )
                )
        );

        assertEquals(
                "Message query returned "
                        + "an invalid row",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectDuplicateMessageIds() {
        List<ConversationMessageListRow> rows = List.of(
                row(
                        9007L,
                        7L,
                        MessageRole.USER,
                        "seven",
                        MessageContentType.TEXT,
                        MessageStatus.COMPLETED,
                        UPDATED_AT
                ),
                row(
                        9007L,
                        6L,
                        MessageRole.USER,
                        "six",
                        MessageContentType.TEXT,
                        MessageStatus.COMPLETED,
                        UPDATED_AT
                )
        );

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.of(DETAIL));
        when(messageMapper.findOwnedMessagePage(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                null,
                4
        )).thenReturn(rows);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.listMessages(
                        Long.toString(CONVERSATION_ID),
                        new ConversationMessagesQuery(
                                3,
                                null
                        )
                )
        );

        assertEquals(
                "Message query returned "
                        + "an invalid row",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectDuplicateOrAscendingSequences() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.of(DETAIL));

        when(messageMapper.findOwnedMessagePage(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                null,
                4
        )).thenReturn(List.of(
                row(
                        9001L,
                        7L,
                        MessageRole.USER,
                        "seven-a",
                        MessageContentType.TEXT,
                        MessageStatus.COMPLETED,
                        UPDATED_AT
                ),
                row(
                        9002L,
                        7L,
                        MessageRole.USER,
                        "seven-b",
                        MessageContentType.TEXT,
                        MessageStatus.COMPLETED,
                        UPDATED_AT
                )
        ));

        IllegalStateException duplicate = assertThrows(
                IllegalStateException.class,
                () -> service.listMessages(
                        Long.toString(CONVERSATION_ID),
                        new ConversationMessagesQuery(
                                3,
                                null
                        )
                )
        );

        assertEquals(
                "Message query returned rows outside "
                        + "strict descending sequence order",
                duplicate.getMessage()
        );

        when(messageMapper.findOwnedMessagePage(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                null,
                4
        )).thenReturn(List.of(
                row(
                        9001L,
                        6L,
                        MessageRole.USER,
                        "six",
                        MessageContentType.TEXT,
                        MessageStatus.COMPLETED,
                        UPDATED_AT
                ),
                row(
                        9002L,
                        7L,
                        MessageRole.USER,
                        "seven",
                        MessageContentType.TEXT,
                        MessageStatus.COMPLETED,
                        UPDATED_AT
                )
        ));

        IllegalStateException ascending = assertThrows(
                IllegalStateException.class,
                () -> service.listMessages(
                        Long.toString(CONVERSATION_ID),
                        new ConversationMessagesQuery(
                                3,
                                null
                        )
                )
        );

        assertEquals(
                "Message query returned rows outside "
                        + "strict descending sequence order",
                ascending.getMessage()
        );
    }

    @Test
    void shouldRejectMoreRowsThanFetchLimit() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.of(DETAIL));
        when(messageMapper.findOwnedMessagePage(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                null,
                4
        )).thenReturn(List.of(
                userMessage(9L),
                userMessage(8L),
                userMessage(7L),
                userMessage(6L),
                userMessage(5L)
        ));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.listMessages(
                        Long.toString(CONVERSATION_ID),
                        new ConversationMessagesQuery(
                                3,
                                null
                        )
                )
        );

        assertEquals(
                "Message query returned more "
                        + "rows than requested",
                exception.getMessage()
        );
    }

    @Test
    void shouldRejectRowsWithMissingRequiredFields() {
        ConversationMessageListRow missingContent =
                new ConversationMessageListRow(
                        9001L,
                        TENANT_ID,
                        CONVERSATION_ID,
                        7L,
                        MessageRole.USER,
                        null,
                        MessageContentType.TEXT,
                        MessageStatus.COMPLETED,
                        UPDATED_AT
                );

        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.of(DETAIL));
        when(messageMapper.findOwnedMessagePage(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                null,
                4
        )).thenReturn(List.of(missingContent));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.listMessages(
                        Long.toString(CONVERSATION_ID),
                        new ConversationMessagesQuery(
                                3,
                                null
                        )
                )
        );

        assertEquals(
                "Message query returned "
                        + "an invalid row",
                exception.getMessage()
        );
    }

    @Test
    void shouldCheckOwnershipBeforeDecodingCursor() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.of(DETAIL));
        when(cursorCodec.decode("cursor-for-5"))
                .thenReturn(
                        new ConversationMessageCursor(
                                CONVERSATION_ID,
                                5L
                        )
                );
        when(messageMapper.findOwnedMessagePage(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                5L,
                4
        )).thenReturn(List.of(
                userMessage(4L),
                userMessage(3L),
                userMessage(2L)
        ));

        service.listMessages(
                Long.toString(CONVERSATION_ID),
                new ConversationMessagesQuery(
                        3,
                        "cursor-for-5"
                )
        );

        InOrder inOrder = inOrder(
                currentActorProvider,
                conversationMapper,
                cursorCodec,
                messageMapper
        );

        inOrder.verify(currentActorProvider)
                .requireCurrentActor();
        inOrder.verify(conversationMapper)
                .findOwnedDetail(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID
                );
        inOrder.verify(cursorCodec)
                .decode("cursor-for-5");
        inOrder.verify(messageMapper)
                .findOwnedMessagePage(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        5L,
                        4
                );
    }

    @Test
    void shouldReturnNotFoundBeforeRejectingMalformedCursor() {
        when(currentActorProvider.requireCurrentActor())
                .thenReturn(ACTOR);
        when(conversationMapper.findOwnedDetail(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.empty());

        assertThrows(
                ConversationNotFoundException.class,
                () -> service.listMessages(
                        Long.toString(CONVERSATION_ID),
                        new ConversationMessagesQuery(
                                3,
                                "malformed"
                        )
                )
        );

        verifyNoInteractions(
                cursorCodec,
                messageMapper
        );
    }

    private static ConversationMessageListRow userMessage(
            long sequenceNo
    ) {
        return row(
                9000L + sequenceNo,
                sequenceNo,
                MessageRole.USER,
                "Message " + sequenceNo,
                MessageContentType.TEXT,
                MessageStatus.COMPLETED,
                UPDATED_AT
        );
    }

    private static ConversationMessageListRow row(
            long id,
            long sequenceNo,
            MessageRole role,
            String content,
            MessageContentType contentType,
            MessageStatus status,
            Instant createdAt
    ) {
        return new ConversationMessageListRow(
                id,
                TENANT_ID,
                CONVERSATION_ID,
                sequenceNo,
                role,
                content,
                contentType,
                status,
                createdAt
        );
    }
}
