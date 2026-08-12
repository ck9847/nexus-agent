package com.nexusagent.conversation.internal;

import com.nexusagent.agent.api.ActiveAgentRuntime;
import com.nexusagent.agent.api.AgentNotFoundException;
import com.nexusagent.agent.api.AgentRuntimeLookup;
import com.nexusagent.agent.domain.AgentModelConfig;
import com.nexusagent.agent.domain.AgentModelProvider;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultPrepareConversationTurnServiceTest {

    private static final long USER_ID = 101L;
    private static final long TENANT_ID = 202L;
    private static final long CONVERSATION_ID = 901L;
    private static final long AGENT_ID = 500L;
    private static final long USER_MESSAGE_ID = 1001L;
    private static final long ASSISTANT_MESSAGE_ID = 1002L;
    private static final int HISTORY_LIMIT = 49;

    private static final String SYSTEM_PROMPT =
            "You are a support agent.";
    private static final String MODEL_NAME = "gpt-5";

    private static final Instant RAW_NOW =
            Instant.parse("2026-08-09T10:15:30.123456Z");

    private static final Instant NOW =
            Instant.parse("2026-08-09T10:15:30.123Z");

    private static final CurrentActor ACTOR =
            new CurrentActor(
                    USER_ID,
                    TENANT_ID,
                    "member",
                    Set.of("MEMBER")
            );

    private static final ConversationTurnStateRow ACTIVE_STATE =
            new ConversationTurnStateRow(
                    CONVERSATION_ID,
                    TENANT_ID,
                    USER_ID,
                    AGENT_ID,
                    ConversationStatus.ACTIVE,
                    2L,
                    7
            );

    private static final AgentModelConfig MODEL_CONFIG =
            new AgentModelConfig(
                    new BigDecimal("0.7"),
                    new BigDecimal("1.0"),
                    4096
            );

    private static final ActiveAgentRuntime AGENT =
            new ActiveAgentRuntime(
                    AGENT_ID,
                    TENANT_ID,
                    "support-agent",
                    SYSTEM_PROMPT,
                    AgentModelProvider.OPENAI,
                    MODEL_NAME,
                    MODEL_CONFIG
            );

    @Mock
    private CurrentActorProvider currentActorProvider;

    @Mock
    private AgentRuntimeLookup agentRuntimeLookup;

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

    private DefaultPrepareConversationTurnService service;

    @BeforeEach
    void setUp() {
        service = new DefaultPrepareConversationTurnService(
                currentActorProvider,
                agentRuntimeLookup,
                idGenerator,
                conversationMapper,
                messageMapper,
                auditLogWriter,
                clock
        );
    }

    @Test
    void shouldPrepareTurnWithTwoConsecutiveMessages() {
        stubHappyPath();

        PreparedConversationTurn result =
                service.prepare(
                        "  901  ",
                        "  Hello, I need help.  "
                );

        ArgumentCaptor<MessageRow> messageCaptor =
                ArgumentCaptor.forClass(MessageRow.class);

        verify(messageMapper, times(2))
                .insert(messageCaptor.capture());

        List<MessageRow> inserted =
                messageCaptor.getAllValues();

        MessageRow userRow = inserted.get(0);
        MessageRow assistantRow = inserted.get(1);

        MessageRow expectedUser = new MessageRow(
                USER_MESSAGE_ID,
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

        MessageRow expectedAssistant = new MessageRow(
                ASSISTANT_MESSAGE_ID,
                TENANT_ID,
                CONVERSATION_ID,
                3L,
                MessageRole.ASSISTANT,
                "",
                MessageContentType.TEXT,
                MessageStatus.CREATING,
                MODEL_NAME,
                null,
                null,
                null,
                NOW
        );

        assertAll(
                () -> assertEquals(
                        expectedUser,
                        userRow
                ),
                () -> assertEquals(
                        expectedAssistant,
                        assistantRow
                ),
                () -> assertNotEquals(
                        userRow.id(),
                        assistantRow.id()
                ),
                () -> assertSame(AGENT, result.agent()),
                () -> assertEquals(
                        TENANT_ID,
                        result.tenantId()
                ),
                () -> assertEquals(
                        USER_ID,
                        result.userId()
                ),
                () -> assertEquals(
                        CONVERSATION_ID,
                        result.conversationId()
                ),
                () -> assertEquals(
                        USER_MESSAGE_ID,
                        result.userMessageId()
                ),
                () -> assertEquals(
                        2L,
                        result.userSequenceNo()
                ),
                () -> assertEquals(
                        ASSISTANT_MESSAGE_ID,
                        result.assistantMessageId()
                ),
                () -> assertEquals(
                        3L,
                        result.assistantSequenceNo()
                ),
                () -> assertEquals(
                        8,
                        result.conversationVersion()
                ),
                () -> assertEquals(NOW, result.preparedAt())
        );
    }

    @Test
    void shouldAdvanceConversationSequenceAndVersion() {
        stubHappyPath();

        PreparedConversationTurn result =
                service.prepare(
                        "901",
                        "Hello"
                );

        verify(conversationMapper)
                .advanceForPreparedTurn(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        2L,
                        7,
                        NOW
                );

        assertEquals(2L, result.userSequenceNo());
        assertEquals(3L, result.assistantSequenceNo());
        assertEquals(8, result.conversationVersion());
    }

    @Test
    void shouldReverseFortyNineHistoryRowsIntoAscendingOrder() {
        stubHappyPath();

        // 覆盖 stubFullSetup 默认的 1 行历史，换成满 49 行
        stubHistory(descendingHistory(49, 98));

        PreparedConversationTurn result =
                service.prepare(
                        "901",
                        "Hello"
                );

        List<ChatModelMessage> messages =
                result.modelRequest().messages();

        assertEquals(
                HISTORY_LIMIT + 1,
                messages.size()
        );

        List<ChatModelMessage> expected =
                new ArrayList<>();

        for (long sequence = 50;
                sequence <= 98;
                sequence++) {
            MessageRole role =
                    sequence % 2 == 0
                            ? MessageRole.USER
                            : MessageRole.ASSISTANT;

            expected.add(
                    role == MessageRole.USER
                            ? ChatModelMessage.user(
                            "message " + sequence
                    )
                            : ChatModelMessage.assistant(
                            "message " + sequence
                    )
            );
        }

        expected.add(ChatModelMessage.user("Hello"));

        assertEquals(expected, messages);

        assertEquals(
                ChatModelMessage.user("message 50"),
                messages.get(0)
        );

        assertEquals(
                ChatModelMessage.user("Hello"),
                messages.get(messages.size() - 1)
        );
    }

    @Test
    void shouldMapAgentModelConfigToChatModelOptions() {
        stubHappyPath();

        PreparedConversationTurn result =
                service.prepare(
                        "901",
                        "Hello"
                );

        assertEquals(
                new ChatModelOptions(
                        new BigDecimal("0.7"),
                        new BigDecimal("1.0"),
                        4096
                ),
                result.modelRequest().options()
        );

        assertEquals(
                MODEL_NAME,
                result.modelRequest().modelName()
        );

        assertEquals(
                SYSTEM_PROMPT,
                result.modelRequest().systemPrompt()
        );
    }

    @Test
    void shouldUseDefaultChatModelOptionsForNullModelConfig() {
        stubActor();
        stubLock();
        stubNoInFlight();

        when(agentRuntimeLookup.requireActiveAgent(
                TENANT_ID,
                AGENT_ID
        )).thenReturn(agentWithConfig(null));

        stubHistory(descendingHistory(1, 5));
        stubIds();
        stubClock();
        stubInserts();
        stubAdvance();

        PreparedConversationTurn result =
                service.prepare(
                        "901",
                        "Hello"
                );

        assertEquals(
                ChatModelOptions.defaults(),
                result.modelRequest().options()
        );
    }

    @Test
    void shouldExposeEmptyTools() {
        stubHappyPath();

        PreparedConversationTurn result =
                service.prepare(
                        "901",
                        "Hello"
        );

        assertEquals(
                List.of(),
                result.modelRequest().tools()
        );
    }

    @Test
    void shouldRejectWhenTurnAlreadyInProgress() {
        stubActor();
        stubLock();

        when(messageMapper
                .existsCreatingAssistantForOwner(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID
                )).thenReturn(true);

        assertThrows(
                ConversationTurnInProgressException.class,
                () -> service.prepare(
                        "901",
                        "Hello"
                )
        );

        verify(messageMapper)
                .existsCreatingAssistantForOwner(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID
                );

        verify(
                messageMapper,
                never()
        ).findRecentCompletedTurnMessages(
                anyLong(),
                anyLong(),
                anyLong(),
                anyInt()
        );

        verify(
                messageMapper,
                never()
        ).insert(any());

        verify(
                conversationMapper,
                never()
        ).advanceForPreparedTurn(
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                anyInt(),
                any()
        );

        verifyNoInteractions(
                agentRuntimeLookup,
                idGenerator,
                auditLogWriter
        );
    }

    @ParameterizedTest
    @MethodSource("missingConversationIds")
    void shouldHideMissingAndCrossTenantConversationAsNotFound(
            String conversationId
    ) {
        stubActor();

        // SQL 已按 tenant_id + user_id 过滤，跨租户与不存在同样返回空
        when(conversationMapper.findOwnedTurnForUpdate(
                anyLong(),
                anyLong(),
                anyLong()
        )).thenReturn(Optional.empty());

        assertThrows(
                ConversationNotFoundException.class,
                () -> service.prepare(
                        conversationId,
                        "Hello"
                )
        );

        verifyNoInteractions(
                agentRuntimeLookup,
                idGenerator,
                messageMapper,
                auditLogWriter,
                clock
        );
    }

    @ParameterizedTest
    @MethodSource("nonActiveStates")
    void shouldRejectCompletedAndArchivedConversation(
            ConversationTurnStateRow row
    ) {
        stubActor();

        when(conversationMapper.findOwnedTurnForUpdate(
                anyLong(),
                anyLong(),
                anyLong()
        )).thenReturn(Optional.of(row));

        ConversationNotActiveException actual =
                assertThrows(
                        ConversationNotActiveException.class,
                        () -> service.prepare(
                                "901",
                                "Hello"
                        )
                );

        assertEquals(
                row.status(),
                actual.currentStatus()
        );

        verifyNoInteractions(
                agentRuntimeLookup,
                idGenerator,
                messageMapper,
                auditLogWriter,
                clock
        );
    }

    @Test
    void shouldPropagateAgentNotFoundWithoutWriting() {
        stubActor();
        stubLock();
        stubNoInFlight();

        AgentNotFoundException failure =
                new AgentNotFoundException();

        when(agentRuntimeLookup.requireActiveAgent(
                TENANT_ID,
                AGENT_ID
        )).thenThrow(failure);

        AgentNotFoundException actual =
                assertThrows(
                        AgentNotFoundException.class,
                        () -> service.prepare(
                                "901",
                                "Hello"
                        )
                );

        assertSame(failure, actual);

        verify(
                messageMapper,
                never()
        ).findRecentCompletedTurnMessages(
                anyLong(),
                anyLong(),
                anyLong(),
                anyInt()
        );

        verify(
                messageMapper,
                never()
        ).insert(any());

        verify(
                conversationMapper,
                never()
        ).advanceForPreparedTurn(
                anyLong(),
                anyLong(),
                anyLong(),
                anyLong(),
                anyInt(),
                any()
        );

        verifyNoInteractions(
                idGenerator,
                auditLogWriter
        );
    }

    @ParameterizedTest
    @MethodSource("mismatchedAgents")
    void shouldRejectAgentRuntimeMismatch(
            ActiveAgentRuntime agent
    ) {
        stubActor();
        stubLock();
        stubNoInFlight();

        when(agentRuntimeLookup.requireActiveAgent(
                TENANT_ID,
                AGENT_ID
        )).thenReturn(agent);

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.prepare(
                                "901",
                                "Hello"
                        )
                );

        assertEquals(
                "Agent runtime does not match conversation",
                exception.getMessage()
        );

        verifyNoInteractions(
                idGenerator,
                auditLogWriter
        );
    }

    @ParameterizedTest
    @MethodSource("outOfScopeStates")
    void shouldRejectStateOutsideOwnershipScope(
            ConversationTurnStateRow row
    ) {
        stubActor();

        when(conversationMapper.findOwnedTurnForUpdate(
                anyLong(),
                anyLong(),
                anyLong()
        )).thenReturn(Optional.of(row));

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.prepare(
                                "901",
                                "Hello"
                        )
                );

        assertEquals(
                "Conversation mapper returned "
                        + "a row outside ownership scope",
                exception.getMessage()
        );

        verifyNoInteractions(
                agentRuntimeLookup,
                idGenerator,
                messageMapper,
                auditLogWriter,
                clock
        );
    }

    @ParameterizedTest
    @MethodSource("invalidStates")
    void shouldRejectInvalidTurnState(
            ConversationTurnStateRow row
    ) {
        stubActor();

        when(conversationMapper.findOwnedTurnForUpdate(
                anyLong(),
                anyLong(),
                anyLong()
        )).thenReturn(Optional.of(row));

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.prepare(
                                "901",
                                "Hello"
                        )
                );

        assertEquals(
                "Conversation mapper returned "
                        + "an invalid turn state",
                exception.getMessage()
        );

        verifyNoInteractions(
                agentRuntimeLookup,
                idGenerator,
                messageMapper,
                auditLogWriter,
                clock
        );
    }

    @Test
    void shouldRejectNullHistoryFromMapper() {
        stubUpToHistory();

        when(messageMapper.findRecentCompletedTurnMessages(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                HISTORY_LIMIT
        )).thenReturn(null);

        NullPointerException exception =
                assertThrows(
                        NullPointerException.class,
                        () -> service.prepare(
                                "901",
                                "Hello"
                        )
                );

        assertEquals(
                "messageMapper must not return null",
                exception.getMessage()
        );

        verifyNoInteractions(idGenerator);
    }

    @Test
    void shouldRejectHistoryWhenTooManyRows() {
        stubUpToHistory();

        when(messageMapper.findRecentCompletedTurnMessages(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                HISTORY_LIMIT
        )).thenReturn(descendingHistory(50, 99));

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.prepare(
                                "901",
                                "Hello"
                        )
                );

        assertEquals(
                "Message mapper returned too many rows",
                exception.getMessage()
        );

        verifyNoInteractions(idGenerator);
    }

    @ParameterizedTest
    @MethodSource("invalidHistory")
    void shouldRejectInvalidHistory(
            List<ConversationTurnMessageRow> rows
    ) {
        stubUpToHistory();

        when(messageMapper.findRecentCompletedTurnMessages(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                HISTORY_LIMIT
        )).thenReturn(rows);

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.prepare(
                                "901",
                                "Hello"
                        )
                );

        assertEquals(
                "Message mapper returned "
                        + "invalid turn history",
                exception.getMessage()
        );

        verifyNoInteractions(idGenerator);
    }

    @ParameterizedTest
    @MethodSource("unsupportedRoles")
    void shouldRejectHistoryWithUnsupportedRole(
            MessageRole role
    ) {
        stubUpToHistory();

        when(messageMapper.findRecentCompletedTurnMessages(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                HISTORY_LIMIT
        )).thenReturn(List.of(
                historyRow(
                        50L,
                        role,
                        "content",
                        MessageStatus.COMPLETED
                )
        ));

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.prepare(
                                "901",
                                "Hello"
                        )
                );

        assertEquals(
                "Message mapper returned "
                        + "an unsupported role",
                exception.getMessage()
        );

        verifyNoInteractions(idGenerator);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void shouldStopWhenUserInsertCountIsUnexpected(
            int affectedRows
    ) {
        stubFullSetup();

        when(messageMapper.insert(any()))
                .thenReturn(affectedRows);

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.prepare(
                                "901",
                                "Hello"
                        )
                );

        assertEquals(
                "Expected one user message "
                        + "row to be affected",
                exception.getMessage()
        );

        verify(messageMapper).insert(any());

        verify(
                conversationMapper,
                never()
        ).advanceForPreparedTurn(
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
    void shouldStopWhenAssistantInsertCountIsUnexpected(
            int affectedRows
    ) {
        stubFullSetup();

        when(messageMapper.insert(any()))
                .thenReturn(1, affectedRows);

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.prepare(
                                "901",
                                "Hello"
                        )
                );

        assertEquals(
                "Expected one assistant placeholder "
                        + "row to be affected",
                exception.getMessage()
        );

        verify(
                conversationMapper,
                never()
        ).advanceForPreparedTurn(
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
        stubFullSetup();

        when(conversationMapper.advanceForPreparedTurn(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                2L,
                7,
                NOW
        )).thenReturn(affectedRows);

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.prepare(
                                "901",
                                "Hello"
                        )
                );

        assertEquals(
                "Expected one conversation "
                        + "row to be affected",
                exception.getMessage()
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
                        () -> service.prepare(
                                "901",
                                "Hello"
                        )
                );

        assertSame(failure, actual);

        verify(messageMapper, times(2)).insert(any());

        verify(conversationMapper)
                .advanceForPreparedTurn(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        2L,
                        7,
                        NOW
                );
    }

    @Test
    void shouldKeepAuditFreeOfContentAndSystemPrompt() {
        stubHappyPath();

        service.prepare("901", "Hello");

        ArgumentCaptor<AuditLogCommand> auditCaptor =
                ArgumentCaptor.forClass(
                        AuditLogCommand.class
                );

        verify(auditLogWriter).write(auditCaptor.capture());

        AuditLogCommand command = auditCaptor.getValue();

        assertEquals(
                AuditActorType.USER,
                command.actorType()
        );
        assertEquals(
                USER_ID,
                command.actorId()
        );
        assertEquals(
                "CONVERSATION_TURN_PREPARED",
                command.action()
        );
        assertEquals(
                "CONVERSATION",
                command.resourceType()
        );
        assertEquals(
                CONVERSATION_ID,
                command.resourceId()
        );
        assertEquals(
                AuditResult.SUCCESS,
                command.result()
        );

        Map<?, ?> details =
                (Map<?, ?>) command.afterData();

        assertFalse(details.containsKey("content"));
        assertFalse(details.containsKey("systemPrompt"));
        assertFalse(details.containsKey("modelConfig"));

        assertEquals("500", details.get("agentId"));
        assertEquals(
                "1001",
                details.get("userMessageId")
        );
        assertEquals(2L, details.get("userSequenceNo"));
        assertEquals(
                "COMPLETED",
                details.get("userStatus")
        );
        assertEquals(
                "1002",
                details.get("assistantMessageId")
        );
        assertEquals(
                3L,
                details.get("assistantSequenceNo")
        );
        assertEquals(
                "CREATING",
                details.get("assistantStatus")
        );
        assertEquals(8, details.get("conversationVersion"));
    }

    @Test
    void shouldPerformOperationsInStrictOrder() {
        stubHappyPath();

        service.prepare("901", "Hello");

        InOrder order = inOrder(
                currentActorProvider,
                agentRuntimeLookup,
                idGenerator,
                conversationMapper,
                messageMapper,
                auditLogWriter
        );

        order.verify(currentActorProvider)
                .requireCurrentActor();

        order.verify(conversationMapper)
                .findOwnedTurnForUpdate(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID
                );

        order.verify(messageMapper)
                .existsCreatingAssistantForOwner(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID
                );

        order.verify(agentRuntimeLookup)
                .requireActiveAgent(
                        TENANT_ID,
                        AGENT_ID
                );

        order.verify(messageMapper)
                .findRecentCompletedTurnMessages(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        HISTORY_LIMIT
                );

        order.verify(idGenerator, times(2)).nextId();

        order.verify(messageMapper, times(2)).insert(any());

        order.verify(conversationMapper)
                .advanceForPreparedTurn(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        2L,
                        7,
                        NOW
                );

        order.verify(auditLogWriter).write(any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "abc",
            "0",
            "-1",
            "  ",
            "9223372036854775808"
    })
    void shouldRejectInvalidConversationIdBeforeLocking(
            String conversationId
    ) {
        stubActor();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.prepare(
                        conversationId,
                        "Hello"
                )
        );

        verifyNoInteractions(
                agentRuntimeLookup,
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
                () -> service.prepare(
                        "901",
                        content
                )
        );

        verifyNoInteractions(
                agentRuntimeLookup,
                idGenerator,
                conversationMapper,
                messageMapper,
                auditLogWriter,
                clock
        );
    }

    private void stubActor() {
        when(currentActorProvider
                .requireCurrentActor())
                .thenReturn(ACTOR);
    }

    private void stubLock() {
        when(conversationMapper.findOwnedTurnForUpdate(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID
        )).thenReturn(Optional.of(ACTIVE_STATE));
    }

    private void stubNoInFlight() {
        when(messageMapper
                .existsCreatingAssistantForOwner(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID
                )).thenReturn(false);
    }

    private void stubHistory(
            List<ConversationTurnMessageRow> rows
    ) {
        when(messageMapper.findRecentCompletedTurnMessages(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                HISTORY_LIMIT
        )).thenReturn(rows);
    }

    private void stubIds() {
        when(idGenerator.nextId())
                .thenReturn(
                        USER_MESSAGE_ID,
                        ASSISTANT_MESSAGE_ID
                );
    }

    private void stubClock() {
        when(clock.instant()).thenReturn(RAW_NOW);
    }

    private void stubInserts() {
        when(messageMapper.insert(any()))
                .thenReturn(1);
    }

    private void stubAdvance() {
        when(conversationMapper.advanceForPreparedTurn(
                CONVERSATION_ID,
                TENANT_ID,
                USER_ID,
                2L,
                7,
                NOW
        )).thenReturn(1);
    }

    private void stubUpToHistory() {
        stubActor();
        stubLock();
        stubNoInFlight();

        when(agentRuntimeLookup.requireActiveAgent(
                TENANT_ID,
                AGENT_ID
        )).thenReturn(AGENT);
    }

    private void stubFullSetup() {
        stubUpToHistory();
        stubHistory(descendingHistory(1, 5));
        stubIds();
        stubClock();
        stubInserts();
    }

    private void stubHappyPath() {
        stubFullSetup();
        stubAdvance();
    }

    private static ActiveAgentRuntime agentWithConfig(
            AgentModelConfig modelConfig
    ) {
        return new ActiveAgentRuntime(
                AGENT_ID,
                TENANT_ID,
                "support-agent",
                SYSTEM_PROMPT,
                AgentModelProvider.OPENAI,
                MODEL_NAME,
                modelConfig
        );
    }

    private static ConversationTurnMessageRow historyRow(
            long sequenceNo,
            MessageRole role,
            String content,
            MessageStatus status
    ) {
        return new ConversationTurnMessageRow(
                sequenceNo,
                role,
                content,
                status
        );
    }

    private static List<ConversationTurnMessageRow>
    descendingHistory(
            int count,
            long topSequence
    ) {
        List<ConversationTurnMessageRow> rows =
                new ArrayList<>();

        for (int i = 0; i < count; i++) {
            long sequence = topSequence - i;

            MessageRole role =
                    sequence % 2 == 0
                            ? MessageRole.USER
                            : MessageRole.ASSISTANT;

            rows.add(historyRow(
                    sequence,
                    role,
                    "message " + sequence,
                    MessageStatus.COMPLETED
            ));
        }

        return rows;
    }

    private static Stream<String> missingConversationIds() {
        return Stream.of(
                "901",
                "999"
        );
    }

    private static Stream<ConversationTurnStateRow>
    nonActiveStates() {
        return Stream.of(
                new ConversationTurnStateRow(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        AGENT_ID,
                        ConversationStatus.COMPLETED,
                        2L,
                        7
                ),
                new ConversationTurnStateRow(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        AGENT_ID,
                        ConversationStatus.ARCHIVED,
                        2L,
                        7
                )
        );
    }

    private static Stream<ActiveAgentRuntime>
    mismatchedAgents() {
        return Stream.of(
                new ActiveAgentRuntime(
                        501L,
                        TENANT_ID,
                        "support-agent",
                        SYSTEM_PROMPT,
                        AgentModelProvider.OPENAI,
                        MODEL_NAME,
                        null
                ),
                new ActiveAgentRuntime(
                        AGENT_ID,
                        999L,
                        "support-agent",
                        SYSTEM_PROMPT,
                        AgentModelProvider.OPENAI,
                        MODEL_NAME,
                        null
                )
        );
    }

    private static Stream<ConversationTurnStateRow>
    outOfScopeStates() {
        return Stream.of(
                new ConversationTurnStateRow(
                        999L,
                        TENANT_ID,
                        USER_ID,
                        AGENT_ID,
                        ConversationStatus.ACTIVE,
                        2L,
                        7
                ),
                new ConversationTurnStateRow(
                        CONVERSATION_ID,
                        999L,
                        USER_ID,
                        AGENT_ID,
                        ConversationStatus.ACTIVE,
                        2L,
                        7
                ),
                new ConversationTurnStateRow(
                        CONVERSATION_ID,
                        TENANT_ID,
                        999L,
                        AGENT_ID,
                        ConversationStatus.ACTIVE,
                        2L,
                        7
                )
        );
    }

    private static Stream<ConversationTurnStateRow>
    invalidStates() {
        return Stream.of(
                new ConversationTurnStateRow(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        0L,
                        ConversationStatus.ACTIVE,
                        2L,
                        7
                ),
                new ConversationTurnStateRow(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        AGENT_ID,
                        null,
                        2L,
                        7
                ),
                new ConversationTurnStateRow(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        AGENT_ID,
                        ConversationStatus.ACTIVE,
                        0L,
                        7
                ),
                new ConversationTurnStateRow(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        AGENT_ID,
                        ConversationStatus.ACTIVE,
                        Long.MAX_VALUE - 1,
                        7
                ),
                new ConversationTurnStateRow(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        AGENT_ID,
                        ConversationStatus.ACTIVE,
                        2L,
                        -1
                ),
                new ConversationTurnStateRow(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        AGENT_ID,
                        ConversationStatus.ACTIVE,
                        2L,
                        Integer.MAX_VALUE
                )
        );
    }

    private static Stream<List<ConversationTurnMessageRow>>
    invalidHistory() {
        return Stream.of(
                // 乱序：非严格递减
                Arrays.asList(
                        historyRow(
                                50L,
                                MessageRole.USER,
                                "content",
                                MessageStatus.COMPLETED
                        ),
                        historyRow(
                                51L,
                                MessageRole.ASSISTANT,
                                "content",
                                MessageStatus.COMPLETED
                        )
                ),
                // 重复序号
                Arrays.asList(
                        historyRow(
                                50L,
                                MessageRole.USER,
                                "content",
                                MessageStatus.COMPLETED
                        ),
                        historyRow(
                                50L,
                                MessageRole.ASSISTANT,
                                "content",
                                MessageStatus.COMPLETED
                        )
                ),
                // 状态非法
                Arrays.asList(
                        historyRow(
                                50L,
                                MessageRole.USER,
                                "content",
                                MessageStatus.CREATING
                        )
                ),
                Arrays.asList(
                        historyRow(
                                50L,
                                MessageRole.ASSISTANT,
                                "content",
                                MessageStatus.FAILED
                        )
                ),
                // content null
                Arrays.asList(
                        historyRow(
                                50L,
                                MessageRole.USER,
                                null,
                                MessageStatus.COMPLETED
                        )
                ),
                // content 空白
                Arrays.asList(
                        historyRow(
                                50L,
                                MessageRole.USER,
                                "   ",
                                MessageStatus.COMPLETED
                        )
                ),
                // 列表含 null
                Arrays.asList(
                        historyRow(
                                50L,
                                MessageRole.USER,
                                "content",
                                MessageStatus.COMPLETED
                        ),
                        (ConversationTurnMessageRow) null
                ),
                // 非法序号 0
                Arrays.asList(
                        historyRow(
                                0L,
                                MessageRole.USER,
                                "content",
                                MessageStatus.COMPLETED
                        )
                )
        );
    }

    private static Stream<MessageRole> unsupportedRoles() {
        return Stream.of(
                MessageRole.SYSTEM,
                MessageRole.TOOL
        );
    }

    private static Stream<Arguments> invalidContent() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of(""),
                Arguments.of("   "),
                Arguments.of("x".repeat(50_001))
        );
    }
}
