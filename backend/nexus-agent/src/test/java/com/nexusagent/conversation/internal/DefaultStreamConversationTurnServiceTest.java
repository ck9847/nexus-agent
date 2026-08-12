package com.nexusagent.conversation.internal;

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
import com.nexusagent.model.api.ChatModelStreamEvent;
import com.nexusagent.model.api.ChatModelStreamHandler;
import com.nexusagent.model.api.ChatTokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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

    private DefaultStreamConversationTurnService service;

    @BeforeEach
    void setUp() {
        service = new DefaultStreamConversationTurnService(
                prepareService,
                gatewayResolver,
                completeService,
                failService
        );
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
                same(PREPARED.modelRequest()),
                any(ChatModelStreamHandler.class)
        );
        verify(completeService).complete(
                same(PREPARED),
                eq("Hello world"),
                eq(FINISH_REASON),
                eq(USAGE)
        );
        verifyNoInteractions(failService);

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
                "Chat model stream did not include "
                        + "a Completed event",
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
                failService
        );
    }
}
