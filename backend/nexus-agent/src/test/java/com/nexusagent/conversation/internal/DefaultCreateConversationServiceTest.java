package com.nexusagent.conversation.internal;

import com.nexusagent.agent.api.ActiveAgentLookup;
import com.nexusagent.agent.api.ActiveAgentReference;
import com.nexusagent.agent.api.AgentNotFoundException;
import com.nexusagent.audit.api.AuditActorType;
import com.nexusagent.audit.api.AuditLogCommand;
import com.nexusagent.audit.api.AuditLogWriter;
import com.nexusagent.audit.api.AuditResult;
import com.nexusagent.common.id.IdGenerator;
import com.nexusagent.common.security.CurrentActor;
import com.nexusagent.common.security.CurrentActorProvider;
import com.nexusagent.conversation.api.CreateConversationRequest;
import com.nexusagent.conversation.api.CreateConversationResponse;
import com.nexusagent.conversation.api.CreatedMessageResponse;
import com.nexusagent.conversation.domain.ConversationStatus;
import com.nexusagent.conversation.domain.MessageContentType;
import com.nexusagent.conversation.domain.MessageRole;
import com.nexusagent.conversation.domain.MessageStatus;
import com.nexusagent.conversation.internal.persistence.ConversationMapper;
import com.nexusagent.conversation.internal.persistence.ConversationRow;
import com.nexusagent.conversation.internal.persistence.MessageMapper;
import com.nexusagent.conversation.internal.persistence.MessageRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultCreateConversationServiceTest {

    private static final long USER_ID = 101L;
    private static final long TENANT_ID = 202L;
    private static final long AGENT_ID = 301L;
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

    private static final ActiveAgentReference AGENT =
            new ActiveAgentReference(
                    AGENT_ID,
                    TENANT_ID,
                    "support-agent"
            );

    @Mock
    private CurrentActorProvider currentActorProvider;

    @Mock
    private ActiveAgentLookup activeAgentLookup;

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

    private DefaultCreateConversationService service;

    @BeforeEach
    void setUp() {
        service = new DefaultCreateConversationService(
                currentActorProvider,
                activeAgentLookup,
                idGenerator,
                conversationMapper,
                messageMapper,
                auditLogWriter,
                clock
        );
    }

    @Test
    void shouldCreateNormalizedConversationAndInitialMessage() {
        stubSuccessfulCreate();

        CreateConversationResponse response =
                service.create(
                        new CreateConversationRequest(
                                "support-agent",
                                "  Production issue  ",
                                "  The API returns HTTP 500.  "
                        )
                );

        ArgumentCaptor<ConversationRow>
                conversationCaptor =
                ArgumentCaptor.forClass(
                        ConversationRow.class
                );

        ArgumentCaptor<MessageRow> messageCaptor =
                ArgumentCaptor.forClass(
                        MessageRow.class
                );

        ArgumentCaptor<AuditLogCommand> auditCaptor =
                ArgumentCaptor.forClass(
                        AuditLogCommand.class
                );

        InOrder order = inOrder(
                conversationMapper,
                messageMapper,
                auditLogWriter
        );

        order.verify(conversationMapper)
                .insert(
                        conversationCaptor.capture()
                );

        order.verify(messageMapper)
                .insert(messageCaptor.capture());

        order.verify(auditLogWriter)
                .write(auditCaptor.capture());

        ConversationRow expectedConversation =
                new ConversationRow(
                        CONVERSATION_ID,
                        TENANT_ID,
                        USER_ID,
                        AGENT_ID,
                        "Production issue",
                        ConversationStatus.ACTIVE,
                        NOW,
                        0,
                        NOW,
                        NOW
                );

        MessageRow expectedMessage = new MessageRow(
                MESSAGE_ID,
                TENANT_ID,
                CONVERSATION_ID,
                1L,
                MessageRole.USER,
                "The API returns HTTP 500.",
                MessageContentType.TEXT,
                MessageStatus.COMPLETED,
                null,
                null,
                null,
                null,
                NOW
        );

        AuditLogCommand expectedAudit =
                new AuditLogCommand(
                        TENANT_ID,
                        AuditActorType.USER,
                        USER_ID,
                        "CONVERSATION_CREATED",
                        "CONVERSATION",
                        CONVERSATION_ID,
                        null,
                        AuditResult.SUCCESS,
                        null,
                        null,
                        null,
                        null,
                        Map.of(
                                "agentId",
                                Long.toString(AGENT_ID),
                                "agentCode",
                                "support-agent",
                                "status",
                                "ACTIVE",
                                "version",
                                0,
                                "initialMessageId",
                                Long.toString(MESSAGE_ID),
                                "initialMessageSequenceNo",
                                1L,
                                "initialMessageRole",
                                "USER",
                                "initialMessageContentType",
                                "TEXT",
                                "initialMessageStatus",
                                "COMPLETED"
                        ),
                        null,
                        null
                );

        CreatedMessageResponse expectedMessageResponse =
                new CreatedMessageResponse(
                        Long.toString(MESSAGE_ID),
                        1L,
                        MessageRole.USER,
                        "The API returns HTTP 500.",
                        MessageContentType.TEXT,
                        MessageStatus.COMPLETED,
                        NOW
                );

        CreateConversationResponse expectedResponse =
                new CreateConversationResponse(
                        Long.toString(CONVERSATION_ID),
                        Long.toString(AGENT_ID),
                        "support-agent",
                        "Production issue",
                        ConversationStatus.ACTIVE,
                        0,
                        NOW,
                        NOW,
                        NOW,
                        expectedMessageResponse
                );

        assertEquals(
                expectedConversation,
                conversationCaptor.getValue()
        );

        assertEquals(
                expectedMessage,
                messageCaptor.getValue()
        );

        assertEquals(
                expectedAudit,
                auditCaptor.getValue()
        );

        assertEquals(
                expectedResponse,
                response
        );

        verify(idGenerator, times(2)).nextId();
        verify(clock).instant();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void shouldNormalizeMissingTitleToNull(
            String title
    ) {
        stubSuccessfulCreate();

        CreateConversationResponse response =
                service.create(
                        request(title, "Hello")
                );

        ArgumentCaptor<ConversationRow> captor =
                ArgumentCaptor.forClass(
                        ConversationRow.class
                );

        verify(conversationMapper)
                .insert(captor.capture());

        assertNull(captor.getValue().title());
        assertNull(response.title());
    }

    @Test
    void shouldRejectNullRequestBeforeResolvingActor() {
        assertThrows(
                NullPointerException.class,
                () -> service.create(null)
        );

        verifyNoInteractions(
                currentActorProvider,
                activeAgentLookup,
                idGenerator,
                conversationMapper,
                messageMapper,
                auditLogWriter,
                clock
        );
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void shouldRejectMissingInitialMessage(
            String initialMessage
    ) {
        stubActor();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.create(
                        request(null, initialMessage)
                )
        );

        verifyNoInteractions(
                activeAgentLookup,
                idGenerator,
                conversationMapper,
                messageMapper,
                auditLogWriter,
                clock
        );
    }

    @Test
    void shouldRejectOversizedInitialMessage() {
        stubActor();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.create(
                        request(
                                null,
                                "m".repeat(50_001)
                        )
                )
        );

        verifyNoInteractions(
                activeAgentLookup,
                idGenerator,
                conversationMapper,
                messageMapper,
                auditLogWriter,
                clock
        );
    }

    @Test
    void shouldRejectOversizedTitle() {
        stubActor();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.create(
                        request(
                                "t".repeat(256),
                                "Hello"
                        )
                )
        );

        verifyNoInteractions(
                activeAgentLookup,
                idGenerator,
                conversationMapper,
                messageMapper,
                auditLogWriter,
                clock
        );
    }

    @Test
    void shouldStopWhenAgentIsMissingOrInactive() {
        stubActor();

        AgentNotFoundException failure =
                new AgentNotFoundException();

        when(activeAgentLookup.requireActiveAgent(
                TENANT_ID,
                "support-agent"
        )).thenThrow(failure);

        AgentNotFoundException actual =
                assertThrows(
                        AgentNotFoundException.class,
                        () -> service.create(
                                request(null, "Hello")
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
    void shouldRejectNullActiveAgentReference() {
        stubActor();

        when(activeAgentLookup.requireActiveAgent(
                TENANT_ID,
                "support-agent"
        )).thenReturn(null);

        assertThrows(
                NullPointerException.class,
                () -> service.create(
                        request(null, "Hello")
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
    void shouldRejectCrossTenantAgentReference() {
        stubActor();

        when(activeAgentLookup.requireActiveAgent(
                TENANT_ID,
                "support-agent"
        )).thenReturn(
                new ActiveAgentReference(
                        AGENT_ID,
                        999L,
                        "support-agent"
                )
        );

        assertThrows(
                IllegalStateException.class,
                () -> service.create(
                        request(null, "Hello")
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
    @ValueSource(ints = {0, 2})
    void shouldRejectUnexpectedConversationInsertCount(
            int affectedRows
    ) {
        stubActorAndAgent();
        stubIdsAndClock();

        when(conversationMapper.insert(any()))
                .thenReturn(affectedRows);

        assertThrows(
                IllegalStateException.class,
                () -> service.create(
                        request(null, "Hello")
                )
        );

        verifyNoInteractions(
                messageMapper,
                auditLogWriter
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void shouldRejectUnexpectedMessageInsertCount(
            int affectedRows
    ) {
        stubActorAndAgent();
        stubIdsAndClock();

        when(conversationMapper.insert(any()))
                .thenReturn(1);

        when(messageMapper.insert(any()))
                .thenReturn(affectedRows);

        assertThrows(
                IllegalStateException.class,
                () -> service.create(
                        request(null, "Hello")
                )
        );

        verify(conversationMapper).insert(any());
        verify(messageMapper).insert(any());
        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void shouldPropagateAuditFailure() {
        stubSuccessfulCreate();

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
                        () -> service.create(
                                request(null, "Hello")
                        )
                );

        assertSame(failure, actual);

        verify(conversationMapper).insert(any());
        verify(messageMapper).insert(any());
        verify(auditLogWriter).write(any());
    }

    private void stubActor() {
        when(currentActorProvider
                .requireCurrentActor())
                .thenReturn(ACTOR);
    }

    private void stubActorAndAgent() {
        stubActor();

        when(activeAgentLookup.requireActiveAgent(
                TENANT_ID,
                "support-agent"
        )).thenReturn(AGENT);
    }

    private void stubIdsAndClock() {
        when(idGenerator.nextId())
                .thenReturn(CONVERSATION_ID)
                .thenReturn(MESSAGE_ID);

        when(clock.instant()).thenReturn(RAW_NOW);
    }

    private void stubSuccessfulCreate() {
        stubActorAndAgent();
        stubIdsAndClock();

        when(conversationMapper.insert(any()))
                .thenReturn(1);

        when(messageMapper.insert(any()))
                .thenReturn(1);
    }

    private static CreateConversationRequest request(
            String title,
            String initialMessage
    ) {
        return new CreateConversationRequest(
                "support-agent",
                title,
                initialMessage
        );
    }
}