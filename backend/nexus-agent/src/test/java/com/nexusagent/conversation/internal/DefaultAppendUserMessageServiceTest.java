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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAppendUserMessageServiceTest {

    private static final long USER_ID = 101L;
    private static final long TENANT_ID = 202L;
    private static final long CONVERSATION_ID = 901L;
    private static final long MESSAGE_ID = 902L;

    private static final Instant RAW_NOW =
            Instant.parse(
                    "2026-08-09T10:15:30.123456Z"
            );

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-09T10:15:30.123Z"
            );

    private static final CurrentActor ACTOR =
            new CurrentActor(
                    USER_ID,
                    TENANT_ID,
                    "member",
                    Set.of("MEMBER")
            );

    private static final ConversationAppendStateRow
            ACTIVE_STATE =
            new ConversationAppendStateRow(
                    CONVERSATION_ID,
                    TENANT_ID,
                    USER_ID,
                    ConversationStatus.ACTIVE,
                    2L,
                    7
            );

    @Mock
    private CurrentActorProvider currentActorProvider;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private ConversationMapper conversationMapper;

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private AuditLogWriter auditLogWriter;

    @Mock
    private Clock clock;

    private DefaultAppendUserMessageService service;

    @BeforeEach
    void setUp() {
        service = new DefaultAppendUserMessageService(
                currentActorProvider,
                idGenerator,
                conversationMapper,
                messageMapper,
                auditLogWriter,
                clock
        );
    }

    @Test
    void shouldAppendNormalizedUserMessageAndAdvanceConversation() {
        stubHappyPath();

        AppendUserMessageResponse response =
                service.append(
                        "  901  ",
                        new AppendUserMessageRequest(
                                "  Hello, I need help.  "
                        )
                );

        ArgumentCaptor<MessageRow> messageCaptor =
                ArgumentCaptor.forClass(MessageRow.class);

        ArgumentCaptor<AuditLogCommand> auditCaptor =
                ArgumentCaptor.forClass(
                        AuditLogCommand.class
                );

        ArgumentCaptor<Long> lockConversationCaptor =
                ArgumentCaptor.forClass(Long.class);

        ArgumentCaptor<Long> lockTenantCaptor =
                ArgumentCaptor.forClass(Long.class);

        ArgumentCaptor<Long> lockUserCaptor =
                ArgumentCaptor.forClass(Long.class);

        ArgumentCaptor<Long> advanceConversationCaptor =
                ArgumentCaptor.forClass(Long.class);

        ArgumentCaptor<Long> advanceTenantCaptor =
                ArgumentCaptor.forClass(Long.class);

        ArgumentCaptor<Long> advanceUserCaptor =
                ArgumentCaptor.forClass(Long.class);

        ArgumentCaptor<Long> advanceSequenceCaptor =
                ArgumentCaptor.forClass(Long.class);

        ArgumentCaptor<Integer> advanceVersionCaptor =
                ArgumentCaptor.forClass(Integer.class);

        ArgumentCaptor<Instant> advanceLastMessageAtCaptor =
                ArgumentCaptor.forClass(Instant.class);

        InOrder order = inOrder(
                conversationMapper,
                messageMapper,
                auditLogWriter
        );

        order.verify(conversationMapper)
                .findOwnedForUpdate(
                        lockConversationCaptor.capture(),
                        lockTenantCaptor.capture(),
                        lockUserCaptor.capture()
                );

        order.verify(messageMapper)
                .insert(messageCaptor.capture());

        order.verify(conversationMapper)
                .advanceMessageSequence(
                        advanceConversationCaptor.capture(),
                        advanceTenantCaptor.capture(),
                        advanceUserCaptor.capture(),
                        advanceSequenceCaptor.capture(),
                        advanceVersionCaptor.capture(),
                        advanceLastMessageAtCaptor.capture()
                );

        order.verify(auditLogWriter)
                .write(auditCaptor.capture());

        assertEquals(
                CONVERSATION_ID,
                lockConversationCaptor.getValue()
        );
        assertEquals(
                TENANT_ID,
                lockTenantCaptor.getValue()
        );
        assertEquals(
                USER_ID,
                lockUserCaptor.getValue()
        );

        MessageRow expectedMessage = new MessageRow(
                MESSAGE_ID,
                TENANT_ID,
                CONVERSATION_ID,
                2L,
                MessageRole.USER,
                "Hello, I need help.",
                MessageContentType.TEXT,
                MessageStatus.COMPLETED,
                null,
                null,
                null,
                null,
                NOW
        );

        assertEquals(
                expectedMessage,
                messageCaptor.getValue()
        );

        assertEquals(
                CONVERSATION_ID,
                advanceConversationCaptor.getValue()
        );
        assertEquals(
                TENANT_ID,
                advanceTenantCaptor.getValue()
        );
        assertEquals(
                USER_ID,
                advanceUserCaptor.getValue()
        );
        assertEquals(
                2L,
                advanceSequenceCaptor.getValue()
        );
        assertEquals(
                7,
                advanceVersionCaptor.getValue()
        );
        assertEquals(
                NOW,
                advanceLastMessageAtCaptor.getValue()
        );

        AuditLogCommand expectedAudit =
                new AuditLogCommand(
                        TENANT_ID,
                        AuditActorType.USER,
                        USER_ID,
                        "CONVERSATION_MESSAGE_APPENDED",
                        "MESSAGE",
                        MESSAGE_ID,
                        null,
                        AuditResult.SUCCESS,
                        null,
                        null,
                        null,
                        null,
                        Map.of(
                                "conversationId",
                                "901",
                                "sequenceNo",
                                2L,
                                "role",
                                "USER",
                                "contentType",
                                "TEXT",
                                "status",
                                "COMPLETED",
                                "conversationVersion",
                                8
                        ),
                        null,
                        null
                );

        assertEquals(
                expectedAudit,
                auditCaptor.getValue()
        );

        assertFalse(
                ((Map<?, ?>) auditCaptor.getValue()
                        .afterData()).containsKey("content")
        );

        AppendUserMessageResponse expectedResponse =
                new AppendUserMessageResponse(
                        "901",
                        8,
                        NOW,
                        new CreatedMessageResponse(
                                Long.toString(MESSAGE_ID),
                                2L,
                                MessageRole.USER,
                                "Hello, I need help.",
                                MessageContentType.TEXT,
                                MessageStatus.COMPLETED,
                                NOW
                        )
                );

        assertEquals(
                expectedResponse,
                response
        );

        verify(idGenerator).nextId();
        verify(clock).instant();
    }

    @Test
    void shouldRejectNullRequestBeforeResolvingActor() {
        assertThrows(
                NullPointerException.class,
                () -> service.append(
                        "901",
                        null
                )
        );

        verifyNoInteractions(
                currentActorProvider,
                idGenerator,
                conversationMapper,
                messageMapper,
                auditLogWriter,
                clock
        );
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "   ",
            "abc",
            "0",
            "-1",
            "9223372036854775808"
    })
    void shouldRejectInvalidConversationIdBeforeLocking(
            String conversationId
    ) {
        stubActor();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.append(
                        conversationId,
                        new AppendUserMessageRequest(
                                "Hello"
                        )
                )
        );

        verifyNoInteractions(
                idGenerator,
                conversationMapper,
                messageMapper,
                auditLogWriter,
                clock
        );
    }

    @ParameterizedTest
    @MethodSource("invalidContent")
    void shouldRejectInvalidContentBeforeLocking(
            String content
    ) {
        stubActor();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.append(
                        "901",
                        new AppendUserMessageRequest(
                                content
                        )
                )
        );

        verifyNoInteractions(
                idGenerator,
                conversationMapper,
                messageMapper,
                auditLogWriter,
                clock
        );
    }

    @Test
    void shouldReturnNotFoundForMissingOwnedConversation() {
        stubActor();

        when(conversationMapper.findOwnedForUpdate(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.empty());

        assertThrows(
                ConversationNotFoundException.class,
                () -> service.append(
                        "901",
                        new AppendUserMessageRequest(
                                "Hello"
                        )
                )
        );

        verify(conversationMapper)
                .findOwnedForUpdate(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID
                );

        verifyNoInteractions(
                idGenerator,
                messageMapper,
                auditLogWriter,
                clock
        );
    }

    @ParameterizedTest
    @MethodSource("outOfScopeRows")
    void shouldRejectMapperRowOutsideOwnershipScope(
            ConversationAppendStateRow row
    ) {
        stubActor();

        when(conversationMapper.findOwnedForUpdate(
                anyLong(),
                anyLong(),
                anyLong()
        )).thenReturn(Optional.of(row));

        assertThrows(
                IllegalStateException.class,
                () -> service.append(
                        "901",
                        new AppendUserMessageRequest(
                                "Hello"
                        )
                )
        );

        verify(conversationMapper)
                .findOwnedForUpdate(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID
                );

        verifyNoInteractions(
                idGenerator,
                messageMapper,
                auditLogWriter,
                clock
        );
    }

    @ParameterizedTest
    @MethodSource("invalidStateRows")
    void shouldRejectInvalidAppendState(
            ConversationAppendStateRow row
    ) {
        stubActor();

        when(conversationMapper.findOwnedForUpdate(
                anyLong(),
                anyLong(),
                anyLong()
        )).thenReturn(Optional.of(row));

        assertThrows(
                IllegalStateException.class,
                () -> service.append(
                        "901",
                        new AppendUserMessageRequest(
                                "Hello"
                        )
                )
        );

        verify(conversationMapper)
                .findOwnedForUpdate(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID
                );

        verifyNoInteractions(
                idGenerator,
                messageMapper,
                auditLogWriter,
                clock
        );
    }

    @ParameterizedTest
    @MethodSource("nonActiveRows")
    void shouldRejectCompletedAndArchivedConversation(
            ConversationAppendStateRow row
    ) {
        stubActor();

        when(conversationMapper.findOwnedForUpdate(
                anyLong(),
                anyLong(),
                anyLong()
        )).thenReturn(Optional.of(row));

        ConversationNotActiveException actual =
                assertThrows(
                        ConversationNotActiveException.class,
                        () -> service.append(
                                "901",
                                new AppendUserMessageRequest(
                                        "Hello"
                                )
                        )
                );

        assertEquals(
                row.status(),
                actual.currentStatus()
        );

        verify(conversationMapper)
                .findOwnedForUpdate(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID
                );

        verifyNoInteractions(
                idGenerator,
                messageMapper,
                auditLogWriter,
                clock
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void shouldStopWhenMessageInsertCountIsUnexpected(
            int affectedRows
    ) {
        stubAppendSetup();

        when(messageMapper.insert(any()))
                .thenReturn(affectedRows);

        assertThrows(
                IllegalStateException.class,
                () -> service.append(
                        "901",
                        new AppendUserMessageRequest(
                                "Hello"
                        )
                )
        );

        verify(conversationMapper)
                .findOwnedForUpdate(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID
                );

        verify(messageMapper).insert(any());

        verify(
                conversationMapper,
                never()
        ).advanceMessageSequence(
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                anyInt(),
                any()
        );

        verifyNoInteractions(auditLogWriter);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void shouldStopWhenConversationAdvanceCountIsUnexpected(
            int affectedRows
    ) {
        stubAppendSetup();

        when(messageMapper.insert(any()))
                .thenReturn(1);

        when(conversationMapper.advanceMessageSequence(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                2L,
                7,
                NOW
        )).thenReturn(affectedRows);

        assertThrows(
                IllegalStateException.class,
                () -> service.append(
                        "901",
                        new AppendUserMessageRequest(
                                "Hello"
                        )
                )
        );

        verify(messageMapper).insert(any());

        verify(conversationMapper)
                .advanceMessageSequence(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        2L,
                        7,
                        NOW
                );

        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldPropagateMessageInsertFailureWithoutAdvancing() {
        stubAppendSetup();

        IllegalStateException failure =
                new IllegalStateException(
                        "Simulated message insert failure"
                );

        when(messageMapper.insert(any()))
                .thenThrow(failure);

        IllegalStateException actual =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.append(
                                "901",
                                new AppendUserMessageRequest(
                                        "Hello"
                                )
                        )
                );

        assertSame(failure, actual);

        verify(messageMapper).insert(any());

        verify(
                conversationMapper,
                never()
        ).advanceMessageSequence(
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                anyInt(),
                any()
        );

        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldPropagateConversationAdvanceFailureWithoutAuditing() {
        stubAppendSetup();

        when(messageMapper.insert(any()))
                .thenReturn(1);

        IllegalStateException failure =
                new IllegalStateException(
                        "Simulated conversation advance failure"
                );

        when(conversationMapper.advanceMessageSequence(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                2L,
                7,
                NOW
        )).thenThrow(failure);

        IllegalStateException actual =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.append(
                                "901",
                                new AppendUserMessageRequest(
                                        "Hello"
                                )
                        )
                );

        assertSame(failure, actual);

        verify(messageMapper).insert(any());

        verify(conversationMapper)
                .advanceMessageSequence(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        2L,
                        7,
                        NOW
                );

        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldPropagateAuditFailure() {
        stubHappyPath();

        IllegalStateException failure =
                new IllegalStateException(
                        "Simulated audit failure"
                );

        doThrow(failure)
                .when(auditLogWriter)
                .write(any());

        IllegalStateException actual =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.append(
                                "901",
                                new AppendUserMessageRequest(
                                        "Hello"
                                )
                        )
                );

        assertSame(failure, actual);

        verify(conversationMapper)
                .findOwnedForUpdate(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID
                );

        verify(messageMapper).insert(any());

        verify(conversationMapper)
                .advanceMessageSequence(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        2L,
                        7,
                        NOW
                );

        verify(auditLogWriter).write(any());
    }

    private void stubActor() {
        when(currentActorProvider
                .requireCurrentActor())
                .thenReturn(ACTOR);
    }

    private void stubAppendSetup() {
        stubActor();

        when(idGenerator.nextId())
                .thenReturn(MESSAGE_ID);

        when(clock.instant()).thenReturn(RAW_NOW);

        when(conversationMapper.findOwnedForUpdate(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.of(ACTIVE_STATE));
    }

    private void stubHappyPath() {
        stubAppendSetup();

        when(messageMapper.insert(any()))
                .thenReturn(1);

        when(conversationMapper.advanceMessageSequence(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                2L,
                7,
                NOW
        )).thenReturn(1);
    }

    @Test
    void shouldStopWhenCurrentActorCannotBeResolved() {
        IllegalStateException failure =
                new IllegalStateException(
                        "No authenticated actor"
                );

        when(currentActorProvider
                .requireCurrentActor())
                .thenThrow(failure);

        IllegalStateException actual =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.append(
                                "901",
                                new AppendUserMessageRequest(
                                        "Hello"
                                )
                        )
                );

        assertSame(failure, actual);

        verifyNoInteractions(
                idGenerator,
                conversationMapper,
                messageMapper,
                auditLogWriter,
                clock
        );
    }

    @Test
    void shouldRejectNullOptionalReturnedByMapper() {
        stubActor();

        when(conversationMapper.findOwnedForUpdate(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(null);

        NullPointerException exception =
                assertThrows(
                        NullPointerException.class,
                        () -> service.append(
                                "901",
                                new AppendUserMessageRequest(
                                        "Hello"
                                )
                        )
                );

        assertEquals(
                "conversationMapper must not return null",
                exception.getMessage()
        );

        verify(conversationMapper)
                .findOwnedForUpdate(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID
                );

        verifyNoInteractions(
                idGenerator,
                messageMapper,
                auditLogWriter,
                clock
        );
    }

    private static Stream<Arguments> invalidContent() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of(""),
                Arguments.of("   "),
                Arguments.of("m".repeat(50_001))
        );
    }

    private static Stream<ConversationAppendStateRow>
    outOfScopeRows() {
        return Stream.of(
                new ConversationAppendStateRow(
                        999L,
                        TENANT_ID,
                        USER_ID,
                        ConversationStatus.ACTIVE,
                        2L,
                        7
                ),
                new ConversationAppendStateRow(
                        CONVERSATION_ID,
                        999L,
                        USER_ID,
                        ConversationStatus.ACTIVE,
                        2L,
                        7
                ),
                new ConversationAppendStateRow(
                        CONVERSATION_ID,
                        TENANT_ID,
                        999L,
                        ConversationStatus.ACTIVE,
                        2L,
                        7
                )
        );
    }

    private static Stream<ConversationAppendStateRow>
    invalidStateRows() {
        return Stream.of(
                new ConversationAppendStateRow(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        null,
                        2L,
                        7
                ),
                new ConversationAppendStateRow(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        ConversationStatus.ACTIVE,
                        0L,
                        7
                ),
                new ConversationAppendStateRow(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        ConversationStatus.ACTIVE,
                        Long.MAX_VALUE,
                        7
                ),
                new ConversationAppendStateRow(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        ConversationStatus.ACTIVE,
                        2L,
                        -1
                ),
                new ConversationAppendStateRow(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        ConversationStatus.ACTIVE,
                        2L,
                        Integer.MAX_VALUE
                )
        );
    }

    private static Stream<ConversationAppendStateRow>
    nonActiveRows() {
        return Stream.of(
                new ConversationAppendStateRow(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        ConversationStatus.COMPLETED,
                        2L,
                        7
                ),
                new ConversationAppendStateRow(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        ConversationStatus.ARCHIVED,
                        2L,
                        7
                )
        );
    }
}
