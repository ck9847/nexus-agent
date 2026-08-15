package com.nexusagent.conversation.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusagent.agent.api.ActiveAgentRuntime;
import com.nexusagent.agent.domain.AgentModelProvider;
import com.nexusagent.conversation.api.ConversationTurnStreamEvent;
import com.nexusagent.conversation.api.ConversationTurnStreamHandler;
import com.nexusagent.model.api.ChatModelErrorCategory;
import com.nexusagent.model.api.ChatModelException;
import com.nexusagent.model.api.ChatModelFinishReason;
import com.nexusagent.model.api.ChatModelGateway;
import com.nexusagent.model.api.ChatModelGatewayResolver;
import com.nexusagent.model.api.ChatModelMessage;
import com.nexusagent.model.api.ChatModelOptions;
import com.nexusagent.model.api.ChatModelRequest;
import com.nexusagent.model.api.ChatModelRole;
import com.nexusagent.model.api.ChatModelStreamEvent;
import com.nexusagent.model.api.ChatModelStreamHandler;
import com.nexusagent.model.api.ChatModelToolCall;
import com.nexusagent.model.api.ChatTokenUsage;
import com.nexusagent.model.api.ChatToolDefinition;
import com.nexusagent.testing.ThrowingMeterRegistry;
import com.nexusagent.ticket.domain.TicketStatus;
import com.nexusagent.tool.api.RegisterToolExecutionCommand;
import com.nexusagent.tool.api.RegisterToolExecutionResult;
import com.nexusagent.tool.api.RegisterToolExecutionService;
import com.nexusagent.tool.domain.ToolExecutionStatus;
import com.nexusagent.tool.internal.AgentToolExecutionContext;
import com.nexusagent.tool.internal.CreateTicketChatToolDefinition;
import com.nexusagent.tool.internal.CreateTicketToolExecutionService;
import com.nexusagent.tool.internal.ExecuteCreateTicketToolResult;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultStreamConversationTurnServiceTest {

    private static final long USER_ID = 101L;
    private static final long TENANT_ID = 202L;
    private static final long CONVERSATION_ID = 901L;
    private static final long AGENT_ID = 500L;
    private static final long USER_MESSAGE_ID = 1001L;
    private static final long ASSISTANT_MESSAGE_ID = 1002L;
    private static final long ASSISTANT_SEQUENCE_NO = 3L;
    private static final long TOOL_EXECUTION_ID = 7001L;
    private static final long TOOL_MESSAGE_ID = 8001L;
    private static final long FINAL_ASSISTANT_MESSAGE_ID = 8002L;
    private static final long CONTINUATION_SEQUENCE_NO = 5L;

    private static final String TOOL_CALL_ID = "call-1";
    private static final String TOOL_NAME = "create_ticket";

    private static final String SYSTEM_PROMPT =
            "You are a support agent.";
    private static final String MODEL_NAME = "gpt-5";

    private static final Instant PREPARED_AT =
            Instant.parse("2026-08-09T10:15:30.123Z");

    private static final Instant COMPLETED_AT =
            Instant.parse("2026-08-09T10:15:31.123Z");

    private static final ChatModelFinishReason FINISH_REASON =
            ChatModelFinishReason.STOP;

    private static final ChatTokenUsage USAGE =
            new ChatTokenUsage(12, 34);

    private static final ChatTokenUsage SECOND_USAGE =
            new ChatTokenUsage(21, 43);

    private static final String IDEMPOTENCY_KEY =
            "tool:v1:" + "a".repeat(64);

    private static final ActiveAgentRuntime AGENT =
            new ActiveAgentRuntime(
                    AGENT_ID,
                    TENANT_ID,
                    "support-agent",
                    SYSTEM_PROMPT,
                    AgentModelProvider.OPENAI,
                    MODEL_NAME,
                    null
            );

    private static final PreparedConversationTurn PREPARED =
            new PreparedConversationTurn(
                    TENANT_ID,
                    USER_ID,
                    CONVERSATION_ID,
                    AGENT,
                    USER_MESSAGE_ID,
                    2L,
                    ASSISTANT_MESSAGE_ID,
                    ASSISTANT_SEQUENCE_NO,
                    8,
                    PREPARED_AT,
                    new ChatModelRequest(
                            MODEL_NAME,
                            SYSTEM_PROMPT,
                            ChatModelOptions.defaults(),
                            List.of(
                                    ChatModelMessage.user("Hello")
                            ),
                            List.of()
                    )
            );

    private static final CompletedConversationTurn COMPLETED =
            new CompletedConversationTurn(
                    TENANT_ID,
                    USER_ID,
                    CONVERSATION_ID,
                    AGENT_ID,
                    ASSISTANT_MESSAGE_ID,
                    ASSISTANT_SEQUENCE_NO,
                    "Hello world",
                    MODEL_NAME,
                    FINISH_REASON,
                    USAGE,
                    PREPARED_AT,
                    COMPLETED_AT
            );

    private static final ChatModelToolCall TOOL_CALL =
            new ChatModelToolCall(
                    TOOL_CALL_ID,
                    TOOL_NAME,
                    new ObjectMapper().createObjectNode()
                            .put("title", "Server down")
            );

    private static final ChatToolDefinition TOOL_DEFINITION =
            new ChatToolDefinition(
                    TOOL_NAME,
                    "Create a support ticket for the current user",
                    new ObjectMapper().createObjectNode()
            );

    private static final ChatModelRequest TOOL_FIRST_REQUEST =
            new ChatModelRequest(
                    MODEL_NAME,
                    SYSTEM_PROMPT,
                    ChatModelOptions.defaults(),
                    PREPARED.modelRequest().messages(),
                    List.of(TOOL_DEFINITION)
            );

    private static final RegisterToolExecutionResult REGISTRATION =
            new RegisterToolExecutionResult(
                    TOOL_EXECUTION_ID,
                    IDEMPOTENCY_KEY,
                    ToolExecutionStatus.PENDING,
                    true,
                    PREPARED_AT
            );

    private static final CompletedConversationToolCall
            COMPLETED_TOOL_CALL =
            new CompletedConversationToolCall(
                    TENANT_ID,
                    USER_ID,
                    CONVERSATION_ID,
                    AGENT_ID,
                    ASSISTANT_MESSAGE_ID,
                    ASSISTANT_SEQUENCE_NO,
                    TOOL_CALL,
                    TOOL_EXECUTION_ID,
                    MODEL_NAME,
                    USAGE,
                    PREPARED_AT,
                    PREPARED_AT
            );

    private static final ExecuteCreateTicketToolResult TOOL_RESULT =
            new ExecuteCreateTicketToolResult(
                    TOOL_EXECUTION_ID,
                    "9001",
                    "TKT-A1",
                    TicketStatus.OPEN,
                    TOOL_MESSAGE_ID,
                    4L,
                    FINAL_ASSISTANT_MESSAGE_ID,
                    CONTINUATION_SEQUENCE_NO,
                    9,
                    PREPARED_AT,
                    false
            );

    private static final ChatModelRequest SECOND_REQUEST =
            new ChatModelRequest(
                    MODEL_NAME,
                    SYSTEM_PROMPT,
                    ChatModelOptions.defaults(),
                    List.of(
                            ChatModelMessage.user("Hello"),
                            new ChatModelMessage(
                                    ChatModelRole.ASSISTANT,
                                    null,
                                    List.of(TOOL_CALL),
                                    null
                            ),
                            new ChatModelMessage(
                                    ChatModelRole.TOOL,
                                    "{\"ticketId\":\"9001\"}",
                                    List.of(),
                                    TOOL_CALL_ID
                            )
                    ),
                    List.of()
            );

    private static final PreparedConversationToolContinuation
            CONTINUATION =
            new PreparedConversationToolContinuation(
                    TENANT_ID,
                    USER_ID,
                    CONVERSATION_ID,
                    AGENT,
                    TOOL_EXECUTION_ID,
                    TOOL_MESSAGE_ID,
                    4L,
                    FINAL_ASSISTANT_MESSAGE_ID,
                    CONTINUATION_SEQUENCE_NO,
                    9,
                    PREPARED_AT,
                    TOOL_CALL,
                    SECOND_REQUEST
            );

    private static final CompletedConversationTurn
            COMPLETED_CONTINUATION =
            new CompletedConversationTurn(
                    TENANT_ID,
                    USER_ID,
                    CONVERSATION_ID,
                    AGENT_ID,
                    FINAL_ASSISTANT_MESSAGE_ID,
                    CONTINUATION_SEQUENCE_NO,
                    "Ticket created",
                    MODEL_NAME,
                    FINISH_REASON,
                    SECOND_USAGE,
                    PREPARED_AT,
                    COMPLETED_AT
            );

    @Mock
    private PrepareConversationTurnService prepareService;

    @Mock
    private ChatModelGatewayResolver gatewayResolver;

    @Mock
    private CompleteConversationTurnService completeService;

    @Mock
    private FailConversationTurnService failService;

    @Mock
    private ChatModelGateway gateway;

    @Mock
    private CreateTicketChatToolDefinition createTicketTool;

    @Mock
    private RegisterToolExecutionService
            registerToolExecutionService;

    @Mock
    private CompleteConversationToolCallService
            completeToolCallService;

    @Mock
    private CreateTicketToolExecutionService toolExecutionService;

    @Mock
    private PrepareConversationToolContinuationService
            continuationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final MockClock meterClock = new MockClock();

    private final ThrowingMeterRegistry meterRegistry =
            new ThrowingMeterRegistry(meterClock);

    private DefaultStreamConversationTurnService service;

    @BeforeEach
    void setUp() {
        service = new DefaultStreamConversationTurnService(
                prepareService,
                gatewayResolver,
                completeService,
                failService,
                objectMapper,
                createTicketTool,
                registerToolExecutionService,
                completeToolCallService,
                toolExecutionService,
                continuationService,
                new ConversationTurnMetrics(meterRegistry)
        );

        lenient().when(createTicketTool.definition())
                .thenReturn(TOOL_DEFINITION);
    }

    @Test
    void shouldStreamSuccessfulTurnWithFullEventOrder() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);
        when(gatewayResolver.requireGateway(
                AgentModelProvider.OPENAI
        )).thenReturn(gateway);

        doAnswer(invocation -> {
            ChatModelStreamHandler modelHandler =
                    invocation.getArgument(1);
            modelHandler.onEvent(
                    new ChatModelStreamEvent.TextDelta("Hello")
            );
            modelHandler.onEvent(
                    new ChatModelStreamEvent.TextDelta(" world")
            );
            modelHandler.onEvent(
                    new ChatModelStreamEvent.Completed(
                            FINISH_REASON,
                            USAGE
                    )
            );
            return null;
        }).when(gateway).stream(any(), any());

        when(completeService.complete(
                same(PREPARED),
                eq("Hello world"),
                eq(FINISH_REASON),
                eq(USAGE)
        )).thenReturn(COMPLETED);

        List<ConversationTurnStreamEvent> received =
                new ArrayList<>();

        service.stream("901", "  Question  ", received::add);

        assertEquals(
                List.of(
                        new ConversationTurnStreamEvent.Started(
                                "901",
                                "500",
                                "1001",
                                2L,
                                "1002",
                                ASSISTANT_SEQUENCE_NO,
                                8,
                                PREPARED_AT
                        ),
                        new ConversationTurnStreamEvent.TextDelta(
                                "Hello"
                        ),
                        new ConversationTurnStreamEvent.TextDelta(
                                " world"
                        ),
                        new ConversationTurnStreamEvent.Completed(
                                "901",
                                "500",
                                "1002",
                                ASSISTANT_SEQUENCE_NO,
                                8,
                                MODEL_NAME,
                                FINISH_REASON,
                                12,
                                34,
                                COMPLETED_AT
                        )
                ),
                received
        );

        verify(gatewayResolver).requireGateway(
                AgentModelProvider.OPENAI
        );
        verify(gateway).stream(
                eq(TOOL_FIRST_REQUEST),
                any(ChatModelStreamHandler.class)
        );
        verify(completeService).complete(
                same(PREPARED),
                eq("Hello world"),
                eq(FINISH_REASON),
                eq(USAGE)
        );
        verifyNoInteractions(failService);

        assertTrue(PREPARED.modelRequest().tools().isEmpty());

        verifyNoInteractions(
                registerToolExecutionService,
                completeToolCallService,
                toolExecutionService,
                continuationService
        );

        InOrder order = inOrder(
                prepareService,
                gateway,
                completeService
        );

        order.verify(prepareService).prepare(any(), any());
        order.verify(gateway).stream(any(), any());
        order.verify(completeService).complete(any(), any(), any(), any());
    }

    @Test
    void shouldFailWithMalformedResponseWhenModelCompletionMissing() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);
        when(gatewayResolver.requireGateway(any()))
                .thenReturn(gateway);

        doAnswer(invocation -> {
            ChatModelStreamHandler modelHandler =
                    invocation.getArgument(1);
            modelHandler.onEvent(
                    new ChatModelStreamEvent.TextDelta("Hello")
            );
            return null;
        }).when(gateway).stream(any(), any());

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                exception.category()
        );
        assertEquals(
                "Chat model stream is malformed",
                exception.getMessage()
        );

        verify(failService).fail(
                same(PREPARED),
                same(exception)
        );
        verifyNoInteractions(completeService);
    }

    @Test
    void shouldFailWhenResolverThrowsProviderUnavailable() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);

        ChatModelException providerFailure =
                new ChatModelException(
                        ChatModelErrorCategory.PROVIDER_UNAVAILABLE,
                        "provider down"
                );

        when(gatewayResolver.requireGateway(any()))
                .thenThrow(providerFailure);

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        assertSame(providerFailure, exception);

        verify(failService).fail(
                same(PREPARED),
                same(providerFailure)
        );
        verifyNoInteractions(gateway, completeService);
    }

    @Test
    void shouldPropagateGatewayChatModelExceptionVerbatim() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);
        when(gatewayResolver.requireGateway(any()))
                .thenReturn(gateway);

        ChatModelException gatewayFailure =
                new ChatModelException(
                        ChatModelErrorCategory.RATE_LIMIT,
                        "rate limited",
                        429,
                        null
                );

        doThrow(gatewayFailure)
                .when(gateway).stream(any(), any());

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        assertSame(gatewayFailure, exception);

        verify(failService).fail(
                same(PREPARED),
                same(gatewayFailure)
        );
        verifyNoInteractions(completeService);
    }

    @Test
    void shouldMapUnexpectedGatewayRuntimeExceptionToMalformedResponse() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);
        when(gatewayResolver.requireGateway(any()))
                .thenReturn(gateway);

        IllegalStateException unexpected =
                new IllegalStateException("boom");

        doThrow(unexpected)
                .when(gateway).stream(any(), any());

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                exception.category()
        );
        assertEquals(
                "Chat model gateway failed unexpectedly",
                exception.getMessage()
        );
        assertSame(unexpected, exception.getCause());

        verify(failService).fail(
                same(PREPARED),
                same(exception)
        );
        verifyNoInteractions(completeService);
    }

    @Test
    void shouldMapStartedHandlerRuntimeFailureAndFailPlaceholder() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);

        IllegalStateException deliveryFailure =
                new IllegalStateException("client disconnected");

        ConversationTurnStreamHandler failingHandler =
                event -> {
                    if (event
                            instanceof
                            ConversationTurnStreamEvent.Started) {
                        throw deliveryFailure;
                    }
                };

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        failingHandler
                )
        );

        assertAll(
                () -> assertEquals(
                        ChatModelErrorCategory.STREAM_INTERRUPTED,
                        exception.category()
                ),
                () -> assertEquals(
                        "Conversation turn stream delivery failed",
                        exception.getMessage()
                ),
                () -> assertSame(
                        deliveryFailure,
                        exception.getCause()
                )
        );

        verify(failService).fail(
                same(PREPARED),
                same(exception)
        );
        verifyNoInteractions(
                gatewayResolver,
                gateway,
                completeService
        );
    }

    @Test
    void shouldNotTrustModelErrorThrownByStartedConsumer() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);

        ChatModelException spoofed =
                new ChatModelException(
                        ChatModelErrorCategory.RATE_LIMIT,
                        "spoofed provider failure"
                );

        ConversationTurnStreamHandler failingHandler =
                event -> {
                    if (event
                            instanceof
                            ConversationTurnStreamEvent.Started) {
                        throw spoofed;
                    }
                };

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        failingHandler
                )
        );

        assertAll(
                () -> assertEquals(
                        ChatModelErrorCategory.STREAM_INTERRUPTED,
                        exception.category()
                ),
                () -> assertEquals(
                        "Conversation turn stream delivery failed",
                        exception.getMessage()
                ),
                () -> assertSame(
                        spoofed,
                        exception.getCause()
                )
        );

        verify(failService).fail(
                same(PREPARED),
                same(exception)
        );
        verifyNoInteractions(
                gatewayResolver,
                gateway,
                completeService
        );
    }

    @Test
    void shouldMapUnexpectedResolverFailureAndFailPlaceholder() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);

        IllegalStateException resolverFailure =
                new IllegalStateException(
                        "resolver implementation failed"
                );

        when(gatewayResolver.requireGateway(any()))
                .thenThrow(resolverFailure);

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        assertAll(
                () -> assertEquals(
                        ChatModelErrorCategory.MALFORMED_RESPONSE,
                        exception.category()
                ),
                () -> assertEquals(
                        "Chat model gateway failed unexpectedly",
                        exception.getMessage()
                ),
                () -> assertSame(
                        resolverFailure,
                        exception.getCause()
                )
        );

        verify(failService).fail(
                same(PREPARED),
                same(exception)
        );
        verifyNoInteractions(gateway, completeService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("protocolViolations")
    void shouldFailPlaceholderForProtocolViolation(
            String scenario,
            Consumer<ChatModelStreamHandler> stream
    ) {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);
        when(gatewayResolver.requireGateway(any()))
                .thenReturn(gateway);

        doAnswer(invocation -> {
            ChatModelStreamHandler modelHandler =
                    invocation.getArgument(1);
            stream.accept(modelHandler);
            return null;
        }).when(gateway).stream(any(), any());

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        assertAll(
                () -> assertEquals(
                        ChatModelErrorCategory.MALFORMED_RESPONSE,
                        exception.category()
                ),
                () -> verify(failService).fail(
                        same(PREPARED),
                        same(exception)
                ),
                () -> verifyNoInteractions(completeService)
        );
    }

    private static Stream<Arguments> protocolViolations() {
        return Stream.of(
                Arguments.of(
                        "tool call delta",
                        (Consumer<ChatModelStreamHandler>) handler ->
                                handler.onEvent(
                                        new ChatModelStreamEvent
                                                .ToolCallDelta(
                                                0,
                                                "call_",
                                                null,
                                                null
                                        )
                                )
                ),
                Arguments.of(
                        "tool calls finish reason",
                        (Consumer<ChatModelStreamHandler>) handler ->
                                handler.onEvent(
                                        new ChatModelStreamEvent
                                                .Completed(
                                                ChatModelFinishReason
                                                        .TOOL_CALLS,
                                                USAGE
                                        )
                                )
                ),
                Arguments.of(
                        "duplicate completed",
                        (Consumer<ChatModelStreamHandler>) handler -> {
                            handler.onEvent(
                                    new ChatModelStreamEvent
                                            .Completed(
                                            FINISH_REASON,
                                            USAGE
                                    )
                            );
                            handler.onEvent(
                                    new ChatModelStreamEvent
                                            .Completed(
                                            FINISH_REASON,
                                            USAGE
                                    )
                            );
                        }
                ),
                Arguments.of(
                        "text delta after completed",
                        (Consumer<ChatModelStreamHandler>) handler -> {
                            handler.onEvent(
                                    new ChatModelStreamEvent
                                            .Completed(
                                            FINISH_REASON,
                                            USAGE
                                    )
                            );
                            handler.onEvent(
                                    new ChatModelStreamEvent
                                            .TextDelta("late")
                            );
                        }
                ),
                Arguments.of(
                        "blank final content",
                        (Consumer<ChatModelStreamHandler>) handler -> {
                            handler.onEvent(
                                    new ChatModelStreamEvent
                                            .TextDelta("   ")
                            );
                            handler.onEvent(
                                    new ChatModelStreamEvent
                                            .Completed(
                                            FINISH_REASON,
                                            USAGE
                                    )
                            );
                        }
                ),
                Arguments.of(
                        "content too large",
                        (Consumer<ChatModelStreamHandler>) handler ->
                                handler.onEvent(
                                        new ChatModelStreamEvent
                                                .TextDelta(
                                                "a".repeat(50_001)
                                        )
                                )
                )
        );
    }

    @Test
    void shouldMapTextDeltaHandlerFailureToStreamInterrupted() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);
        when(gatewayResolver.requireGateway(any()))
                .thenReturn(gateway);

        doAnswer(invocation -> {
            ChatModelStreamHandler modelHandler =
                    invocation.getArgument(1);
            modelHandler.onEvent(
                    new ChatModelStreamEvent.TextDelta("Hello")
            );
            return null;
        }).when(gateway).stream(any(), any());

        ConversationTurnStreamHandler failingHandler =
                event -> {
                    if (event
                            instanceof
                            ConversationTurnStreamEvent.TextDelta) {
                        throw new IllegalStateException(
                                "client disconnected"
                        );
                    }
                };

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        failingHandler
                )
        );

        assertEquals(
                ChatModelErrorCategory.STREAM_INTERRUPTED,
                exception.category()
        );
        assertEquals(
                "Conversation turn stream delivery failed",
                exception.getMessage()
        );
        assertTrue(exception.getCause()
                instanceof IllegalStateException);
        assertEquals(
                "client disconnected",
                exception.getCause().getMessage()
        );

        verify(failService).fail(
                same(PREPARED),
                same(exception)
        );
        verifyNoInteractions(completeService);
    }

    @Test
    void shouldFailBeforeCompletionWhenConsumerWasCancelled() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);

        when(gatewayResolver.requireGateway(any()))
                .thenReturn(gateway);

        AtomicBoolean cancelled = new AtomicBoolean();

        ConversationTurnStreamHandler handler =
                new ConversationTurnStreamHandler() {
                    @Override
                    public void onEvent(
                            ConversationTurnStreamEvent event
                    ) {
                    }

                    @Override
                    public boolean isCancellationRequested() {
                        return cancelled.get();
                    }
                };

        doAnswer(invocation -> {
            ChatModelStreamHandler modelHandler =
                    invocation.getArgument(1);

            modelHandler.onEvent(
                    new ChatModelStreamEvent.TextDelta(
                            "completed model output"
                    )
            );

            modelHandler.onEvent(
                    new ChatModelStreamEvent.Completed(
                            FINISH_REASON,
                            USAGE
                    )
            );

            // 模拟客户端断流，Gateway 忽略线程中断后正常返回。
            cancelled.set(true);

            return null;
        }).when(gateway).stream(any(), any());

        ChatModelException exception = assertThrows(
                ChatModelException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        handler
                )
        );

        assertEquals(
                ChatModelErrorCategory.STREAM_INTERRUPTED,
                exception.category()
        );

        verify(failService).fail(
                same(PREPARED),
                same(exception)
        );

        verifyNoInteractions(completeService);
    }

    @Test
    void shouldNotCallFailWhenCompleteServiceFails() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);
        when(gatewayResolver.requireGateway(any()))
                .thenReturn(gateway);

        doAnswer(invocation -> {
            ChatModelStreamHandler modelHandler =
                    invocation.getArgument(1);
            modelHandler.onEvent(
                    new ChatModelStreamEvent.TextDelta("Hello")
            );
            modelHandler.onEvent(
                    new ChatModelStreamEvent.Completed(
                            FINISH_REASON,
                            USAGE
                    )
            );
            return null;
        }).when(gateway).stream(any(), any());

        IllegalStateException completionFailure =
                new IllegalStateException("completion boom");

        when(completeService.complete(
                any(),
                any(),
                any(),
                any()
        )).thenThrow(completionFailure);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        assertSame(completionFailure, exception);

        verifyNoInteractions(failService);
    }

    @Test
    void shouldNotCallFailWhenFinalCompletedHandlerFails() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);
        when(gatewayResolver.requireGateway(any()))
                .thenReturn(gateway);

        doAnswer(invocation -> {
            ChatModelStreamHandler modelHandler =
                    invocation.getArgument(1);
            modelHandler.onEvent(
                    new ChatModelStreamEvent.TextDelta("Hello")
            );
            modelHandler.onEvent(
                    new ChatModelStreamEvent.Completed(
                            FINISH_REASON,
                            USAGE
                    )
            );
            return null;
        }).when(gateway).stream(any(), any());

        when(completeService.complete(
                same(PREPARED),
                eq("Hello"),
                eq(FINISH_REASON),
                eq(USAGE)
        )).thenReturn(COMPLETED);

        ConversationTurnStreamHandler failingHandler =
                event -> {
                    if (event
                            instanceof
                            ConversationTurnStreamEvent.Completed) {
                        throw new IllegalStateException(
                                "final delivery failed"
                        );
                    }
                };

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        failingHandler
                )
        );

        assertEquals(
                "final delivery failed",
                exception.getMessage()
        );

        verify(completeService).complete(
                same(PREPARED),
                eq("Hello"),
                eq(FINISH_REASON),
                eq(USAGE)
        );
        verifyNoInteractions(failService);
    }

    @Test
    void shouldPrioritizeFinalizationFailureWithModelFailureSuppressed() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);
        when(gatewayResolver.requireGateway(any()))
                .thenReturn(gateway);

        ChatModelException modelFailure =
                new ChatModelException(
                        ChatModelErrorCategory.RATE_LIMIT,
                        "rate limited",
                        429,
                        null
                );

        doThrow(modelFailure)
                .when(gateway).stream(any(), any());

        IllegalStateException finalizationFailure =
                new IllegalStateException("finalization boom");

        doThrow(finalizationFailure)
                .when(failService).fail(
                        same(PREPARED),
                        any()
                );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        assertSame(finalizationFailure, exception);

        Throwable[] suppressed = exception.getSuppressed();
        assertEquals(1, suppressed.length);
        assertTrue(suppressed[0] instanceof ChatModelException);
        assertEquals(
                ChatModelErrorCategory.RATE_LIMIT,
                ((ChatModelException) suppressed[0]).category()
        );

        verifyNoInteractions(completeService);
    }

    @Test
    void shouldCompleteToolRoundAndStreamContinuation() {
        stubToolRoundHappyPath();

        when(completeService.complete(
                same(CONTINUATION),
                eq("Ticket created"),
                eq(FINISH_REASON),
                eq(SECOND_USAGE)
        )).thenReturn(COMPLETED_CONTINUATION);

        List<ConversationTurnStreamEvent> received =
                new ArrayList<>();

        service.stream("901", "Question", received::add);

        assertEquals(
                List.of(
                        new ConversationTurnStreamEvent.Started(
                                "901",
                                "500",
                                "1001",
                                2L,
                                "1002",
                                ASSISTANT_SEQUENCE_NO,
                                8,
                                PREPARED_AT
                        ),
                        new ConversationTurnStreamEvent.TextDelta(
                                "Ticket "
                        ),
                        new ConversationTurnStreamEvent.TextDelta(
                                "created"
                        ),
                        new ConversationTurnStreamEvent.Completed(
                                "901",
                                "500",
                                Long.toString(
                                        FINAL_ASSISTANT_MESSAGE_ID
                                ),
                                CONTINUATION_SEQUENCE_NO,
                                9,
                                MODEL_NAME,
                                FINISH_REASON,
                                21,
                                43,
                                COMPLETED_AT
                        )
                ),
                received
        );

        ArgumentCaptor<RegisterToolExecutionCommand>
                commandCaptor =
                ArgumentCaptor.forClass(
                        RegisterToolExecutionCommand.class
                );

        verify(registerToolExecutionService).register(
                commandCaptor.capture()
        );

        RegisterToolExecutionCommand command =
                commandCaptor.getValue();

        assertEquals(CONVERSATION_ID, command.conversationId());
        assertEquals(AGENT_ID, command.agentId());
        assertEquals(
                ASSISTANT_MESSAGE_ID,
                command.requestMessageId()
        );
        assertEquals(TOOL_CALL_ID, command.toolCallId());
        assertEquals(TOOL_NAME, command.toolName());
        assertEquals(TOOL_CALL.arguments(), command.input());
        assertFalse(command.approvalRequired());
        assertNull(command.traceId());

        ArgumentCaptor<ChatModelRequest> requestCaptor =
                ArgumentCaptor.forClass(ChatModelRequest.class);

        verify(gateway, times(2)).stream(
                requestCaptor.capture(),
                any(ChatModelStreamHandler.class)
        );

        List<ChatModelRequest> requests =
                requestCaptor.getAllValues();

        assertEquals(TOOL_FIRST_REQUEST, requests.get(0));
        assertEquals(CONTINUATION.modelRequest(), requests.get(1));

        // 第一轮恰好注入一个 create_ticket 工具定义。
        assertEquals(1, requests.get(0).tools().size());
        assertEquals(
                TOOL_NAME,
                requests.get(0).tools().get(0).name()
        );

        // 第二轮禁止工具，杜绝无限工具循环。
        assertTrue(requests.get(1).tools().isEmpty());

        // 工具执行上下文的身份全部来自 prepared。
        ArgumentCaptor<AgentToolExecutionContext> contextCaptor =
                ArgumentCaptor.forClass(
                        AgentToolExecutionContext.class
                );

        verify(toolExecutionService).execute(
                contextCaptor.capture()
        );

        AgentToolExecutionContext executionContext =
                contextCaptor.getValue();

        assertEquals(TENANT_ID, executionContext.tenantId());
        assertEquals(USER_ID, executionContext.requesterUserId());
        assertEquals(
                CONVERSATION_ID,
                executionContext.conversationId()
        );
        assertEquals(AGENT_ID, executionContext.agentId());
        assertEquals(
                ASSISTANT_MESSAGE_ID,
                executionContext.requestMessageId()
        );
        assertEquals(
                TOOL_EXECUTION_ID,
                executionContext.toolExecutionId()
        );
        assertEquals(TOOL_CALL_ID, executionContext.toolCallId());

        // SSE 客户端绝不能收到工具调用增量。
        assertTrue(received.stream().allMatch(event ->
                event instanceof ConversationTurnStreamEvent.Started
                        || event instanceof ConversationTurnStreamEvent
                        .TextDelta
                        || event instanceof ConversationTurnStreamEvent
                        .Completed
        ));

        verify(completeService).complete(
                same(CONTINUATION),
                eq("Ticket created"),
                eq(FINISH_REASON),
                eq(SECOND_USAGE)
        );
        verifyNoInteractions(failService);

        InOrder order = inOrder(
                registerToolExecutionService,
                completeToolCallService,
                toolExecutionService,
                continuationService,
                gateway,
                completeService
        );

        order.verify(gateway).stream(any(), any());
        order.verify(registerToolExecutionService).register(any());
        order.verify(completeToolCallService).complete(
                any(),
                any(),
                any(),
                anyLong()
        );
        order.verify(toolExecutionService).execute(any());
        order.verify(continuationService).prepare(
                any(),
                any(),
                any()
        );
        order.verify(gateway).stream(any(), any());
        order.verify(completeService).complete(
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void shouldFailFirstAssistantWhenRegistrationFails() {
        stubFirstModelToolCall();

        IllegalStateException registrationFailure =
                new IllegalStateException("register boom");

        when(registerToolExecutionService.register(any()))
                .thenThrow(registrationFailure);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        assertSame(registrationFailure, thrown);

        ArgumentCaptor<ChatModelException> failureCaptor =
                ArgumentCaptor.forClass(ChatModelException.class);

        verify(failService).fail(
                same(PREPARED),
                failureCaptor.capture()
        );

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                failureCaptor.getValue().category()
        );
        assertSame(
                registrationFailure,
                failureCaptor.getValue().getCause()
        );

        verifyNoInteractions(
                completeToolCallService,
                toolExecutionService,
                continuationService,
                completeService
        );
    }

    @Test
    void shouldCompensateWhenToolCallCompletionFails() {
        stubFirstModelToolCall();

        when(registerToolExecutionService.register(any()))
                .thenReturn(REGISTRATION);

        IllegalStateException completionFailure =
                new IllegalStateException("completion boom");

        when(completeToolCallService.complete(
                any(),
                any(),
                any(),
                anyLong()
        )).thenThrow(completionFailure);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        assertSame(completionFailure, thrown);

        ArgumentCaptor<AgentToolExecutionContext> contextCaptor =
                ArgumentCaptor.forClass(
                        AgentToolExecutionContext.class
                );

        verify(toolExecutionService).failPending(
                contextCaptor.capture(),
                same(completionFailure)
        );

        AgentToolExecutionContext context =
                contextCaptor.getValue();

        assertEquals(TENANT_ID, context.tenantId());
        assertEquals(USER_ID, context.requesterUserId());
        assertEquals(CONVERSATION_ID, context.conversationId());
        assertEquals(AGENT_ID, context.agentId());
        assertEquals(
                ASSISTANT_MESSAGE_ID,
                context.requestMessageId()
        );
        assertEquals(
                TOOL_EXECUTION_ID,
                context.toolExecutionId()
        );
        assertEquals(TOOL_CALL_ID, context.toolCallId());

        ArgumentCaptor<ChatModelException> failureCaptor =
                ArgumentCaptor.forClass(ChatModelException.class);

        verify(failService).fail(
                same(PREPARED),
                failureCaptor.capture()
        );

        assertSame(
                completionFailure,
                failureCaptor.getValue().getCause()
        );

        verify(toolExecutionService, never()).execute(any());
        verifyNoInteractions(continuationService, completeService);
    }

    @Test
    void shouldNotFailFirstAssistantWhenToolExecutionFails() {
        stubFirstModelToolCall();
        stubRegistrationAndToolCallCompletion();

        IllegalStateException executionFailure =
                new IllegalStateException("execution boom");

        when(toolExecutionService.execute(any()))
                .thenThrow(executionFailure);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        assertSame(executionFailure, thrown);

        verify(completeToolCallService).complete(
                any(),
                any(),
                any(),
                anyLong()
        );
        verifyNoInteractions(failService);
        verifyNoInteractions(continuationService, completeService);
    }

    @Test
    void shouldFailContinuationWhenSecondModelFails() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);
        when(gatewayResolver.requireGateway(any()))
                .thenReturn(gateway);

        ChatModelException secondModelFailure =
                new ChatModelException(
                        ChatModelErrorCategory.RATE_LIMIT,
                        "rate limited",
                        429,
                        null
                );

        doAnswer(invocation -> {
            ChatModelStreamHandler modelHandler =
                    invocation.getArgument(1);
            modelHandler.onEvent(
                    new ChatModelStreamEvent.ToolCallDelta(
                            0,
                            TOOL_CALL_ID,
                            TOOL_NAME,
                            "{\"title\":\"Server down\"}"
                    )
            );
            modelHandler.onEvent(
                    new ChatModelStreamEvent.Completed(
                            ChatModelFinishReason.TOOL_CALLS,
                            USAGE
                    )
            );
            return null;
        }).doThrow(secondModelFailure)
                .when(gateway).stream(any(), any());

        when(registerToolExecutionService.register(any()))
                .thenReturn(REGISTRATION);
        when(completeToolCallService.complete(
                any(),
                any(),
                any(),
                anyLong()
        )).thenReturn(COMPLETED_TOOL_CALL);
        when(toolExecutionService.execute(any()))
                .thenReturn(TOOL_RESULT);
        when(continuationService.prepare(
                any(),
                any(),
                any()
        )).thenReturn(CONTINUATION);

        ChatModelException thrown = assertThrows(
                ChatModelException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        assertSame(secondModelFailure, thrown);

        verify(failService).fail(
                same(CONTINUATION),
                same(secondModelFailure)
        );
        verify(failService, never()).fail(
                same(PREPARED),
                any()
        );
        verifyNoInteractions(completeService);
    }

    @Test
    void shouldFailPendingWhenCancelledBeforeTicketCreation() {
        stubFirstModelToolCall();

        when(registerToolExecutionService.register(any()))
                .thenReturn(REGISTRATION);

        AtomicBoolean cancelled = new AtomicBoolean();

        when(completeToolCallService.complete(
                any(),
                any(),
                any(),
                anyLong()
        )).thenAnswer(invocation -> {
            cancelled.set(true);
            return COMPLETED_TOOL_CALL;
        });

        ConversationTurnStreamHandler handler =
                new ConversationTurnStreamHandler() {
                    @Override
                    public void onEvent(
                            ConversationTurnStreamEvent event
                    ) {
                    }

                    @Override
                    public boolean isCancellationRequested() {
                        return cancelled.get();
                    }
                };

        ChatModelException thrown = assertThrows(
                ChatModelException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        handler
                )
        );

        assertEquals(
                ChatModelErrorCategory.STREAM_INTERRUPTED,
                thrown.category()
        );

        verify(toolExecutionService).failPending(
                any(),
                same(thrown)
        );
        verify(toolExecutionService, never()).execute(any());
        verifyNoInteractions(failService);
        verifyNoInteractions(continuationService, completeService);
    }

    @Test
    void shouldFailContinuationPlaceholderWhenPreparationFails() {
        stubFirstModelToolCall();
        stubRegistrationToolCallCompletionAndExecution();

        IllegalStateException preparationFailure =
                new IllegalStateException("codec boom");

        when(continuationService.prepare(
                any(),
                any(),
                any()
        )).thenThrow(preparationFailure);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        assertSame(preparationFailure, thrown);

        // 工具执行已经成功。
        verify(toolExecutionService).execute(any());

        // failService 收到的是最终 ASSISTANT 占位坐标。
        ArgumentCaptor<AssistantMessageCompletionTarget>
                targetCaptor =
                ArgumentCaptor.forClass(
                        AssistantMessageCompletionTarget.class
                );
        ArgumentCaptor<ChatModelException> failureCaptor =
                ArgumentCaptor.forClass(ChatModelException.class);

        verify(failService).fail(
                targetCaptor.capture(),
                failureCaptor.capture()
        );

        AssistantMessageCompletionTarget target =
                targetCaptor.getValue();

        assertEquals(
                FINAL_ASSISTANT_MESSAGE_ID,
                target.assistantMessageId()
        );
        assertEquals(
                CONTINUATION_SEQUENCE_NO,
                target.assistantSequenceNo()
        );
        assertEquals(9, target.conversationVersion());

        ChatModelException failure = failureCaptor.getValue();

        assertEquals(
                ChatModelErrorCategory.MALFORMED_RESPONSE,
                failure.category()
        );
        assertSame(preparationFailure, failure.getCause());

        // 不调用第二次 Gateway，也不调用最终 completeService。
        verify(gateway, times(1)).stream(any(), any());
        verifyNoInteractions(completeService);

        // 不错误失败首轮 ASSISTANT。
        verify(failService, never()).fail(
                same(PREPARED),
                any()
        );
    }

    @Test
    void shouldPrioritizeContinuationFinalizationFailure() {
        stubFirstModelToolCall();
        stubRegistrationToolCallCompletionAndExecution();

        IllegalStateException preparationFailure =
                new IllegalStateException("codec boom");

        when(continuationService.prepare(
                any(),
                any(),
                any()
        )).thenThrow(preparationFailure);

        IllegalStateException finalizationFailure =
                new IllegalStateException("finalization boom");

        doThrow(finalizationFailure)
                .when(failService).fail(any(), any());

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        // failService 失败时，最终失败为主异常。
        assertSame(finalizationFailure, thrown);

        // continuation preparation failure 保留在 suppressed 链中。
        assertEquals(1, thrown.getSuppressed().length);

        Throwable suppressed = thrown.getSuppressed()[0];

        assertTrue(suppressed instanceof ChatModelException);

        assertSame(
                preparationFailure,
                suppressed.getCause()
        );

        verify(gateway, times(1)).stream(any(), any());
        verifyNoInteractions(completeService);
    }

    @Test
    void shouldNotCallFailWhenContinuationCompleteFails() {
        stubToolRoundHappyPath();

        IllegalStateException completionFailure =
                new IllegalStateException("completion boom");

        when(completeService.complete(
                any(),
                any(),
                any(),
                any()
        )).thenThrow(completionFailure);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        assertSame(completionFailure, thrown);
        verifyNoInteractions(failService);
    }

    @Test
    void shouldFailContinuationWhenCancelledBeforeSecondModel() {
        stubFirstModelToolCall();
        stubRegistrationToolCallCompletionAndExecution();

        AtomicBoolean cancelled = new AtomicBoolean();

        when(continuationService.prepare(
                any(),
                any(),
                any()
        )).thenAnswer(invocation -> {
            cancelled.set(true);
            return CONTINUATION;
        });

        ConversationTurnStreamHandler handler =
                new ConversationTurnStreamHandler() {
                    @Override
                    public void onEvent(
                            ConversationTurnStreamEvent event
                    ) {
                    }

                    @Override
                    public boolean isCancellationRequested() {
                        return cancelled.get();
                    }
                };

        ChatModelException thrown = assertThrows(
                ChatModelException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        handler
                )
        );

        assertEquals(
                ChatModelErrorCategory.STREAM_INTERRUPTED,
                thrown.category()
        );

        verify(failService).fail(
                same(CONTINUATION),
                same(thrown)
        );
        verify(failService, never()).fail(
                same(PREPARED),
                any()
        );
        verify(gateway, times(1)).stream(any(), any());
        verifyNoInteractions(completeService);
    }

    @Test
    void shouldRejectNullHandlerBeforePrepare() {
        assertThrows(
                NullPointerException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        null
                )
        );

        verifyNoInteractions(
                prepareService,
                gatewayResolver,
                completeService,
                failService,
                createTicketTool,
                registerToolExecutionService,
                completeToolCallService,
                toolExecutionService,
                continuationService
        );
    }

    @Test
    void shouldSuspendTransactionsAroundModelCalls() throws Exception {
        Method streamMethod =
                DefaultStreamConversationTurnService.class
                        .getMethod(
                                "stream",
                                String.class,
                                String.class,
                                ConversationTurnStreamHandler.class
                        );

        Transactional transactional =
                streamMethod.getAnnotation(
                        Transactional.class
                );

        assertNotNull(transactional);
        assertEquals(
                Propagation.NOT_SUPPORTED,
                transactional.propagation()
        );
    }

    @Test
    void shouldRecordCompletedTextOutcomeOnce() {
        stubTextRoundSuccess();

        service.stream("901", "Question", event -> {
        });

        assertEquals(
                1.0,
                turnCount(
                        ConversationTurnOutcome.COMPLETED_TEXT
                )
        );
        assertEquals(
                0.0,
                turnCount(
                        ConversationTurnOutcome.COMPLETED_TOOL
                )
        );
        assertEquals(
                0.0,
                turnCount(
                        ConversationTurnOutcome.MODEL_FAILED
                )
        );
        assertEquals(
                0.0,
                turnCount(
                        ConversationTurnOutcome.TOOL_FAILED
                )
        );
        assertEquals(
                0.0,
                turnCount(
                        ConversationTurnOutcome.CLIENT_DISCONNECTED
                )
        );
        assertEquals(
                0.0,
                turnCount(
                        ConversationTurnOutcome.INTERNAL_FAILED
                )
        );
    }

    @Test
    void shouldRecordCompletedToolOutcomeSeparately() {
        stubToolRoundHappyPath();

        when(completeService.complete(
                same(CONTINUATION),
                eq("Ticket created"),
                eq(FINISH_REASON),
                eq(SECOND_USAGE)
        )).thenReturn(COMPLETED_CONTINUATION);

        service.stream("901", "Question", event -> {
        });

        assertEquals(
                1.0,
                turnCount(
                        ConversationTurnOutcome.COMPLETED_TOOL
                )
        );
        assertEquals(
                0.0,
                turnCount(
                        ConversationTurnOutcome.COMPLETED_TEXT
                )
        );
    }

    @Test
    void shouldRecordModelFailedOutcome() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);
        when(gatewayResolver.requireGateway(any()))
                .thenReturn(gateway);

        doThrow(new ChatModelException(
                ChatModelErrorCategory.RATE_LIMIT,
                "rate limited",
                429,
                null
        )).when(gateway).stream(any(), any());

        assertThrows(
                ChatModelException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        assertEquals(
                1.0,
                turnCount(
                        ConversationTurnOutcome.MODEL_FAILED
                )
        );
        assertEquals(
                0.0,
                turnCount(
                        ConversationTurnOutcome.COMPLETED_TEXT
                )
        );
    }

    @Test
    void shouldRecordClientDisconnectedOutcome() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);
        when(gatewayResolver.requireGateway(any()))
                .thenReturn(gateway);

        doThrow(new ChatModelException(
                ChatModelErrorCategory.STREAM_INTERRUPTED,
                "Conversation turn stream delivery failed"
        )).when(gateway).stream(any(), any());

        assertThrows(
                ChatModelException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        assertEquals(
                1.0,
                turnCount(
                        ConversationTurnOutcome.CLIENT_DISCONNECTED
                )
        );
        assertEquals(
                0.0,
                turnCount(
                        ConversationTurnOutcome.MODEL_FAILED
                )
        );
    }

    @Test
    void shouldRecordToolFailedOutcomeWhenExecutionFails() {
        stubFirstModelToolCall();
        stubRegistrationAndToolCallCompletion();

        when(toolExecutionService.execute(any()))
                .thenThrow(new IllegalStateException(
                        "execution boom"
                ));

        assertThrows(
                IllegalStateException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        assertEquals(
                1.0,
                turnCount(
                        ConversationTurnOutcome.TOOL_FAILED
                )
        );
        assertEquals(
                0.0,
                turnCount(
                        ConversationTurnOutcome.COMPLETED_TOOL
                )
        );
    }

    @Test
    void shouldNotRecordSuccessWhenCompleteServiceFails() {
        stubTextRoundSuccess();

        when(completeService.complete(
                any(),
                any(),
                any(),
                any()
        )).thenThrow(new IllegalStateException(
                "completion boom"
        ));

        assertThrows(
                IllegalStateException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        assertEquals(
                1.0,
                turnCount(
                        ConversationTurnOutcome.INTERNAL_FAILED
                )
        );
        assertEquals(
                0.0,
                turnCount(
                        ConversationTurnOutcome.COMPLETED_TEXT
                )
        );
    }

    @Test
    void shouldRecordSingleOutcomeWhenFailServiceAlsoFails() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);
        when(gatewayResolver.requireGateway(any()))
                .thenReturn(gateway);

        doThrow(new ChatModelException(
                ChatModelErrorCategory.RATE_LIMIT,
                "rate limited",
                429,
                null
        )).when(gateway).stream(any(), any());

        doThrow(new IllegalStateException(
                "finalization boom"
        )).when(failService).fail(any(), any());

        assertThrows(
                IllegalStateException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        // failService 再失败：仍只记录一个最终 outcome。
        assertEquals(
                1.0,
                turnCount(
                        ConversationTurnOutcome.MODEL_FAILED
                )
        );
        assertEquals(
                0.0,
                turnCount(
                        ConversationTurnOutcome.INTERNAL_FAILED
                )
        );
        assertEquals(
                0.0,
                turnCount(
                        ConversationTurnOutcome.COMPLETED_TEXT
                )
        );
    }

    @Test
    void shouldMeasureDurationWithMonotonicClock() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);
        when(gatewayResolver.requireGateway(any()))
                .thenReturn(gateway);

        doAnswer(invocation -> {
            ChatModelStreamHandler modelHandler =
                    invocation.getArgument(1);

            // turn 执行期间单调时钟推进 5 秒；
            // Instant 完全未被使用。
            meterClock.addSeconds(5);

            modelHandler.onEvent(
                    new ChatModelStreamEvent.TextDelta("Hello")
            );
            modelHandler.onEvent(
                    new ChatModelStreamEvent.Completed(
                            FINISH_REASON,
                            USAGE
                    )
            );
            return null;
        }).when(gateway).stream(any(), any());

        when(completeService.complete(
                any(),
                any(),
                any(),
                any()
        )).thenReturn(COMPLETED);

        service.stream("901", "Question", event -> {
        });

        assertEquals(
                5.0,
                turnTotalSeconds(
                        ConversationTurnOutcome.COMPLETED_TEXT
                ),
                0.001
        );
    }

    @Test
    void shouldCompleteTurnWhenTimerStopFails() {
        // turn 已经成功：timer 创建失败必须被吞掉，
        // handler 仍收到 Completed，绝不能触发 failService。
        meterRegistry.throwOnTimerCreation();

        stubTextRoundSuccess();

        List<ConversationTurnStreamEvent> received =
                new ArrayList<>();

        service.stream("901", "Question", received::add);

        assertTrue(received.stream().anyMatch(event ->
                event
                        instanceof
                        ConversationTurnStreamEvent.Completed
        ));

        verify(completeService).complete(
                same(PREPARED),
                eq("Hello"),
                eq(FINISH_REASON),
                eq(USAGE)
        );
        verifyNoInteractions(failService);
    }

    @Test
    void shouldKeepModelFailureWhenTimerStopAlsoFails() {
        // 模型本身失败，同时 timer 停止抛错：
        // 对外仍是原始 ChatModelException，
        // 指标异常不能覆盖原异常，FAILED placeholder 正常持久化。
        meterRegistry.throwOnTimerCreation();

        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);
        when(gatewayResolver.requireGateway(any()))
                .thenReturn(gateway);

        ChatModelException modelFailure =
                new ChatModelException(
                        ChatModelErrorCategory.RATE_LIMIT,
                        "rate limited",
                        429,
                        null
                );

        doThrow(modelFailure)
                .when(gateway).stream(any(), any());

        ChatModelException thrown = assertThrows(
                ChatModelException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        assertSame(modelFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);

        verify(failService).fail(
                same(PREPARED),
                same(modelFailure)
        );
        verifyNoInteractions(completeService);
    }

    @Test
    void shouldCompleteTurnWhenTimerStartFails() {
        // Timer 启动失败：退化为 no-op 观察，
        // turn 仍完整成功，绝不抛指标异常。
        meterRegistry.throwOnTimerStart();

        stubTextRoundSuccess();

        List<ConversationTurnStreamEvent> received =
                new ArrayList<>();

        service.stream("901", "Question", received::add);

        assertTrue(received.stream().anyMatch(event ->
                event
                        instanceof
                        ConversationTurnStreamEvent.Completed
        ));

        verify(completeService).complete(
                same(PREPARED),
                eq("Hello"),
                eq(FINISH_REASON),
                eq(USAGE)
        );
        verifyNoInteractions(failService);
    }

    @Test
    void shouldExposeOnlyLowCardinalityTagsOnModelMetric() {
        stubTextRoundSuccess();

        service.stream("901", "Question", event -> {
        });

        // 再跑一次失败轮，覆盖 failure 时序的标签。
        when(gatewayResolver.requireGateway(any()))
                .thenReturn(gateway);
        doThrow(new ChatModelException(
                ChatModelErrorCategory.RATE_LIMIT,
                "rate limited",
                429,
                null
        )).when(gateway).stream(any(), any());

        assertThrows(
                ChatModelException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        Set<String> allowedTagKeys = Set.of(
                ConversationTurnMetrics.TAG_OUTCOME,
                ConversationTurnMetrics.TAG_PROVIDER,
                ConversationTurnMetrics.TAG_ERROR_CATEGORY
        );

        meterRegistry.find(
                        ConversationTurnMetrics.MODEL_CALL_METRIC
                )
                .meters()
                .forEach(meter -> meter.getId()
                        .getTags()
                        .forEach(tag -> assertTrue(
                                allowedTagKeys.contains(
                                        tag.getKey()
                                ),
                                "high-cardinality tag "
                                        + "forbidden: "
                                        + tag.getKey()
                        )));
    }

    @Test
    void shouldCountModelCallSuccess() {
        stubTextRoundSuccess();

        service.stream("901", "Question", event -> {
        });

        assertEquals(
                1.0,
                modelCallCount(
                        ConversationTurnMetrics.OUTCOME_SUCCESS,
                        "OPENAI",
                        ConversationTurnMetrics
                                .ERROR_CATEGORY_NONE
                )
        );
        assertEquals(
                0.0,
                modelCallCount(
                        ConversationTurnMetrics.OUTCOME_FAILURE,
                        "OPENAI",
                        ConversationTurnMetrics
                                .ERROR_CATEGORY_NONE
                )
        );
    }

    @Test
    void shouldCountModelCallFailure() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);
        when(gatewayResolver.requireGateway(any()))
                .thenReturn(gateway);

        doThrow(new ChatModelException(
                ChatModelErrorCategory.RATE_LIMIT,
                "rate limited",
                429,
                null
        )).when(gateway).stream(any(), any());

        assertThrows(
                ChatModelException.class,
                () -> service.stream(
                        "901",
                        "Question",
                        event -> {
                        }
                )
        );

        assertEquals(
                1.0,
                modelCallCount(
                        ConversationTurnMetrics.OUTCOME_FAILURE,
                        "OPENAI",
                        ConversationTurnMetrics
                                .ERROR_CATEGORY_RATE_LIMIT
                )
        );
        assertEquals(
                0.0,
                modelCallCount(
                        ConversationTurnMetrics.OUTCOME_SUCCESS,
                        "OPENAI",
                        ConversationTurnMetrics
                                .ERROR_CATEGORY_RATE_LIMIT
                )
        );
    }

    private void stubTextRoundSuccess() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);
        when(gatewayResolver.requireGateway(any()))
                .thenReturn(gateway);

        doAnswer(invocation -> {
            ChatModelStreamHandler modelHandler =
                    invocation.getArgument(1);
            modelHandler.onEvent(
                    new ChatModelStreamEvent.TextDelta("Hello")
            );
            modelHandler.onEvent(
                    new ChatModelStreamEvent.Completed(
                            FINISH_REASON,
                            USAGE
                    )
            );
            return null;
        }).when(gateway).stream(any(), any());

        when(completeService.complete(
                any(),
                any(),
                any(),
                any()
        )).thenReturn(COMPLETED);
    }

    private double modelCallCount(
            String outcome,
            String provider,
            String errorCategory
    ) {
        Timer timer = meterRegistry.find(
                        ConversationTurnMetrics.MODEL_CALL_METRIC
                )
                .tag(
                        ConversationTurnMetrics.TAG_OUTCOME,
                        outcome
                )
                .tag(
                        ConversationTurnMetrics.TAG_PROVIDER,
                        provider
                )
                .tag(
                        ConversationTurnMetrics
                                .TAG_ERROR_CATEGORY,
                        errorCategory
                )
                .timer();

        return timer == null ? 0.0 : timer.count();
    }

    private double turnCount(ConversationTurnOutcome outcome) {
        Timer timer = meterRegistry.find(
                        ConversationTurnMetrics
                                .TURN_DURATION_METRIC
                )
                .tag("outcome", outcome.name())
                .timer();

        return timer == null ? 0.0 : timer.count();
    }

    private double turnTotalSeconds(
            ConversationTurnOutcome outcome
    ) {
        Timer timer = meterRegistry.find(
                        ConversationTurnMetrics
                                .TURN_DURATION_METRIC
                )
                .tag("outcome", outcome.name())
                .timer();

        assertNotNull(timer);
        return timer.totalTime(TimeUnit.SECONDS);
    }

    private void stubFirstModelToolCall() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);
        when(gatewayResolver.requireGateway(any()))
                .thenReturn(gateway);

        doAnswer(invocation -> {
            ChatModelStreamHandler modelHandler =
                    invocation.getArgument(1);
            modelHandler.onEvent(
                    new ChatModelStreamEvent.ToolCallDelta(
                            0,
                            TOOL_CALL_ID,
                            TOOL_NAME,
                            "{\"title\":\"Server down\"}"
                    )
            );
            modelHandler.onEvent(
                    new ChatModelStreamEvent.Completed(
                            ChatModelFinishReason.TOOL_CALLS,
                            USAGE
                    )
            );
            return null;
        }).when(gateway).stream(any(), any());
    }

    private void stubRegistrationAndToolCallCompletion() {
        when(registerToolExecutionService.register(any()))
                .thenReturn(REGISTRATION);

        when(completeToolCallService.complete(
                any(),
                any(),
                any(),
                anyLong()
        )).thenReturn(COMPLETED_TOOL_CALL);
    }

    private void stubRegistrationToolCallCompletionAndExecution() {
        stubRegistrationAndToolCallCompletion();

        when(toolExecutionService.execute(any()))
                .thenReturn(TOOL_RESULT);
    }

    private void stubToolRoundHappyPath() {
        when(prepareService.prepare(any(), any()))
                .thenReturn(PREPARED);
        when(gatewayResolver.requireGateway(any()))
                .thenReturn(gateway);

        doAnswer(invocation -> {
            ChatModelStreamHandler modelHandler =
                    invocation.getArgument(1);
            modelHandler.onEvent(
                    new ChatModelStreamEvent.ToolCallDelta(
                            0,
                            TOOL_CALL_ID,
                            TOOL_NAME,
                            "{\"title\":\"Server down\"}"
                    )
            );
            modelHandler.onEvent(
                    new ChatModelStreamEvent.Completed(
                            ChatModelFinishReason.TOOL_CALLS,
                            USAGE
                    )
            );
            return null;
        }).doAnswer(invocation -> {
            ChatModelStreamHandler modelHandler =
                    invocation.getArgument(1);
            modelHandler.onEvent(
                    new ChatModelStreamEvent.TextDelta("Ticket ")
            );
            modelHandler.onEvent(
                    new ChatModelStreamEvent.TextDelta("created")
            );
            modelHandler.onEvent(
                    new ChatModelStreamEvent.Completed(
                            FINISH_REASON,
                            SECOND_USAGE
                    )
            );
            return null;
        }).when(gateway).stream(any(), any());

        when(registerToolExecutionService.register(any()))
                .thenReturn(REGISTRATION);
        when(completeToolCallService.complete(
                any(),
                any(),
                any(),
                anyLong()
        )).thenReturn(COMPLETED_TOOL_CALL);
        when(toolExecutionService.execute(any()))
                .thenReturn(TOOL_RESULT);
        when(continuationService.prepare(
                any(),
                any(),
                any()
        )).thenReturn(CONTINUATION);
    }
}
