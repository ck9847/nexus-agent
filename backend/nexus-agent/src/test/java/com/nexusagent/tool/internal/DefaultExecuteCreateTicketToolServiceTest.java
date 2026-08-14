package com.nexusagent.tool.internal;

import com.nexusagent.ticket.domain.TicketPriority;
import com.nexusagent.ticket.domain.TicketStatus;
import com.nexusagent.tool.api.ToolExecutionApprovalRequiredException;
import com.nexusagent.tool.api.ToolExecutionInProgressException;
import com.nexusagent.tool.api.ToolExecutionNotFoundException;
import com.nexusagent.tool.api.ToolExecutionTerminalStateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultExecuteCreateTicketToolServiceTest {

    private static final long TENANT_ID = 202L;
    private static final long REQUESTER_USER_ID = 101L;
    private static final long CONVERSATION_ID = 901L;
    private static final long AGENT_ID = 500L;
    private static final long REQUEST_MESSAGE_ID = 1001L;
    private static final long TOOL_EXECUTION_ID = 7001L;
    private static final long TOOL_MESSAGE_ID = 8001L;
    private static final long FINAL_ASSISTANT_MESSAGE_ID = 8002L;

    private static final Instant NOW =
            Instant.parse("2026-08-13T10:15:30.123Z");

    @Mock
    private CreateTicketToolExecutionTransactions transactions;

    private DefaultExecuteCreateTicketToolService service;

    @BeforeEach
    void setUp() {
        service = new DefaultExecuteCreateTicketToolService(
                transactions,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldReturnReplayImmediately() {
        ExecuteCreateTicketToolResult replay =
                new ExecuteCreateTicketToolResult(
                        TOOL_EXECUTION_ID,
                        "9001",
                        "TKT-A1",
                        TicketStatus.OPEN,
                        TOOL_MESSAGE_ID,
                        3L,
                        FINAL_ASSISTANT_MESSAGE_ID,
                        4L,
                        2,
                        NOW,
                        true
                );

        ClaimedCreateTicketToolExecution replayClaim =
                ClaimedCreateTicketToolExecution.replay(
                        context(),
                        NOW,
                        replay
                );

        when(transactions.claim(context()))
                .thenReturn(replayClaim);

        ExecuteCreateTicketToolResult result =
                service.execute(context());

        assertSame(replay, result);

        verify(transactions, never()).succeed(any());
        verify(transactions, never()).fail(
                any(),
                any()
        );
    }

    @Test
    void shouldSucceedFreshClaim() {
        ClaimedCreateTicketToolExecution claim =
                new ClaimedCreateTicketToolExecution(
                        context(),
                        arguments(),
                        NOW,
                        null
                );

        ExecuteCreateTicketToolResult expected =
                new ExecuteCreateTicketToolResult(
                        TOOL_EXECUTION_ID,
                        "9001",
                        "TKT-A1",
                        TicketStatus.OPEN,
                        TOOL_MESSAGE_ID,
                        3L,
                        FINAL_ASSISTANT_MESSAGE_ID,
                        4L,
                        2,
                        NOW,
                        false
                );

        when(transactions.claim(context()))
                .thenReturn(claim);
        when(transactions.succeed(claim))
                .thenReturn(expected);

        ExecuteCreateTicketToolResult result =
                service.execute(context());

        assertSame(expected, result);

        verify(transactions, never()).fail(
                any(),
                any()
        );
    }

    @Test
    void shouldFinalizeInvalidInputFailureFromClaim() {
        IllegalArgumentException inputFailure =
                new IllegalArgumentException("bad input");

        when(transactions.claim(context()))
                .thenThrow(inputFailure);

        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.execute(context())
                );

        assertSame(inputFailure, thrown);

        ArgumentCaptor<CreateTicketToolFailure> failureCaptor =
                ArgumentCaptor.forClass(
                        CreateTicketToolFailure.class
                );

        verify(transactions).fail(
                any(),
                failureCaptor.capture()
        );

        CreateTicketToolFailure failure =
                failureCaptor.getValue();

        assertEquals(
                "INVALID_TOOL_INPUT",
                failure.errorCode()
        );
        assertEquals(
                "Create ticket tool input is invalid",
                failure.safeMessage()
        );
        assertEquals(NOW, failure.failedAt());
    }

    @Test
    void shouldFinalizeExecutionFailureWithSafeFields() {
        ClaimedCreateTicketToolExecution claim =
                new ClaimedCreateTicketToolExecution(
                        context(),
                        arguments(),
                        NOW,
                        null
                );

        IllegalStateException executionFailure =
                new IllegalStateException(
                        "provider-secret-must-not-leak"
                );

        when(transactions.claim(context()))
                .thenReturn(claim);
        when(transactions.succeed(claim))
                .thenThrow(executionFailure);

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.execute(context())
                );

        assertSame(executionFailure, thrown);

        ArgumentCaptor<CreateTicketToolFailure> failureCaptor =
                ArgumentCaptor.forClass(
                        CreateTicketToolFailure.class
                );

        verify(transactions).fail(
                any(),
                failureCaptor.capture()
        );

        CreateTicketToolFailure failure =
                failureCaptor.getValue();

        assertEquals(
                "CREATE_TICKET_TOOL_FAILED",
                failure.errorCode()
        );
        assertEquals(
                "Create ticket tool execution failed",
                failure.safeMessage()
        );
        assertFalse(
                failure.safeMessage()
                        .contains("provider-secret")
        );
        assertFalse(
                failure.errorCode()
                        .contains("provider-secret")
        );
    }

    @Test
    void shouldFinalizeIllegalArgumentFromSucceedAsInvalidInput() {
        ClaimedCreateTicketToolExecution claim =
                new ClaimedCreateTicketToolExecution(
                        context(),
                        arguments(),
                        NOW,
                        null
                );

        IllegalArgumentException executionFailure =
                new IllegalArgumentException("schema boom");

        when(transactions.claim(context()))
                .thenReturn(claim);
        when(transactions.succeed(claim))
                .thenThrow(executionFailure);

        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.execute(context())
                );

        assertSame(executionFailure, thrown);

        ArgumentCaptor<CreateTicketToolFailure> failureCaptor =
                ArgumentCaptor.forClass(
                        CreateTicketToolFailure.class
                );

        verify(transactions).fail(
                any(),
                failureCaptor.capture()
        );

        CreateTicketToolFailure failure =
                failureCaptor.getValue();

        assertEquals(
                "INVALID_TOOL_INPUT",
                failure.errorCode()
        );
        assertEquals(
                "Create ticket tool input is invalid",
                failure.safeMessage()
        );
    }

    @ParameterizedTest
    @MethodSource("claimStateExceptions")
    void shouldPropagateClaimStateExceptionsWithoutFailing(
            RuntimeException claimException
    ) {
        when(transactions.claim(context()))
                .thenThrow(claimException);

        RuntimeException thrown =
                assertThrows(
                        claimException.getClass(),
                        () -> service.execute(context())
                );

        assertSame(claimException, thrown);

        verify(transactions, never()).fail(
                any(),
                any()
        );
        verify(transactions, never()).succeed(any());
    }

    @Test
    void shouldUseFinalizationExceptionAsPrimaryWithSuppressed() {
        ClaimedCreateTicketToolExecution claim =
                new ClaimedCreateTicketToolExecution(
                        context(),
                        arguments(),
                        NOW,
                        null
                );

        IllegalStateException executionFailure =
                new IllegalStateException("execution boom");
        IllegalStateException finalizationFailure =
                new IllegalStateException("finalization boom");

        when(transactions.claim(context()))
                .thenReturn(claim);
        when(transactions.succeed(claim))
                .thenThrow(executionFailure);

        doThrow(finalizationFailure)
                .when(transactions)
                .fail(any(), any());

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.execute(context())
                );

        assertSame(finalizationFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(
                executionFailure,
                thrown.getSuppressed()[0]
        );
    }

    @Test
    void shouldNotSelfSuppressWhenSameFailureInstance() {
        ClaimedCreateTicketToolExecution claim =
                new ClaimedCreateTicketToolExecution(
                        context(),
                        arguments(),
                        NOW,
                        null
                );

        IllegalStateException sharedFailure =
                new IllegalStateException("shared boom");

        when(transactions.claim(context()))
                .thenReturn(claim);
        when(transactions.succeed(claim))
                .thenThrow(sharedFailure);

        doThrow(sharedFailure)
                .when(transactions)
                .fail(any(), any());

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.execute(context())
                );

        assertSame(sharedFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    void shouldRejectNullContext() {
        assertThrows(
                NullPointerException.class,
                () -> service.execute(null)
        );

        verify(transactions, never()).claim(any());
    }

    @Test
    void shouldImplementToolExecutionServiceContract() {
        assertInstanceOf(
                CreateTicketToolExecutionService.class,
                service
        );
    }

    @Test
    void shouldFailPendingWithInvalidInputClassification() {
        IllegalArgumentException inputFailure =
                new IllegalArgumentException("bad input");

        service.failPending(context(), inputFailure);

        ArgumentCaptor<CreateTicketToolFailure> failureCaptor =
                ArgumentCaptor.forClass(
                        CreateTicketToolFailure.class
                );

        verify(transactions).fail(
                any(),
                failureCaptor.capture()
        );

        CreateTicketToolFailure failure =
                failureCaptor.getValue();

        assertEquals(
                "INVALID_TOOL_INPUT",
                failure.errorCode()
        );
        assertEquals(
                "Create ticket tool input is invalid",
                failure.safeMessage()
        );
        assertEquals(NOW, failure.failedAt());
    }

    @Test
    void shouldFailPendingWithSafeExecutionFailure() {
        IllegalStateException executionFailure =
                new IllegalStateException(
                        "provider-secret-must-not-leak"
                );

        service.failPending(context(), executionFailure);

        ArgumentCaptor<CreateTicketToolFailure> failureCaptor =
                ArgumentCaptor.forClass(
                        CreateTicketToolFailure.class
                );

        verify(transactions).fail(
                any(),
                failureCaptor.capture()
        );

        CreateTicketToolFailure failure =
                failureCaptor.getValue();

        assertEquals(
                "CREATE_TICKET_TOOL_FAILED",
                failure.errorCode()
        );
        assertEquals(
                "Create ticket tool execution failed",
                failure.safeMessage()
        );
        assertFalse(
                failure.safeMessage()
                        .contains("provider-secret")
        );
        assertFalse(
                failure.errorCode()
                        .contains("provider-secret")
        );
        assertEquals(NOW, failure.failedAt());
    }

    @Test
    void shouldUseFinalizationExceptionAsPrimaryInFailPending() {
        IllegalStateException compensationTrigger =
                new IllegalStateException(
                        "tool call completion boom"
                );
        IllegalStateException finalizationFailure =
                new IllegalStateException("finalization boom");

        doThrow(finalizationFailure)
                .when(transactions)
                .fail(any(), any());

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.failPending(
                                context(),
                                compensationTrigger
                        )
                );

        assertSame(finalizationFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(
                compensationTrigger,
                thrown.getSuppressed()[0]
        );
    }

    @Test
    void shouldNotSelfSuppressInFailPendingWhenSameInstance() {
        IllegalStateException sharedFailure =
                new IllegalStateException("shared boom");

        doThrow(sharedFailure)
                .when(transactions)
                .fail(any(), any());

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.failPending(
                                context(),
                                sharedFailure
                        )
                );

        assertSame(sharedFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    void shouldRejectNullContextInFailPending() {
        assertThrows(
                NullPointerException.class,
                () -> service.failPending(
                        null,
                        new IllegalStateException("boom")
                )
        );

        verify(transactions, never()).fail(
                any(),
                any()
        );
    }

    @Test
    void shouldRejectNullFailureInFailPending() {
        assertThrows(
                NullPointerException.class,
                () -> service.failPending(
                        context(),
                        null
                )
        );

        verify(transactions, never()).fail(
                any(),
                any()
        );
    }

    private static AgentToolExecutionContext context() {
        return new AgentToolExecutionContext(
                TENANT_ID,
                REQUESTER_USER_ID,
                CONVERSATION_ID,
                AGENT_ID,
                REQUEST_MESSAGE_ID,
                TOOL_EXECUTION_ID,
                "call-1"
        );
    }

    private static CreateTicketToolArguments arguments() {
        return new CreateTicketToolArguments(
                "Server down",
                "It is down",
                TicketPriority.HIGH
        );
    }

    private static Stream<RuntimeException> claimStateExceptions() {
        return Stream.of(
                new ToolExecutionNotFoundException(),
                new ToolExecutionInProgressException(),
                new ToolExecutionApprovalRequiredException(),
                new ToolExecutionTerminalStateException()
        );
    }
}
